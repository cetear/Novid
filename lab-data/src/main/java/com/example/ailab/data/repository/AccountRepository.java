package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.mapper.UserMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * 账户与 token 权威存储。所有管理变更先锁控制行，避免最后 ADMIN 竞态。
 */
@Repository
public class AccountRepository implements UserStorePort, AuthTokenStorePort {
    private final SqlSupport sql;
    private final UserMapper mapper;

    /**
     * MyBatis-Plus 常规读取，关键事务使用同一数据源显式 SQL。
     */
    public AccountRepository(SqlSupport sql, UserMapper mapper) {
        this.sql = sql;
        this.mapper = mapper;
    }

    /**
     * 登录凭据仅向账户用例返回。
     */
    public Optional<AccountCredential> credential(String name) {
        return sql.jdbc.query("SELECT * FROM users WHERE username=?", (r, n) -> new AccountCredential(SqlSupport.user(r, n), r.getString("password_hash")), name).stream().findFirst();
    }

    /**
     * 将内部 PO 转换为无凭证契约。
     */
    public Optional<UserSnapshot> user(long id) {
        var p = mapper.selectById(id);
        return p == null ? Optional.empty() : Optional.of(new UserSnapshot(p.id, p.username, UserContext.Role.valueOf(p.role), p.enabled, p.permissionVersion, p.passwordChangeRequired));
    }

    /**
     * 空系统初始化串行化，已有账户明确拒绝。
     */
    @Transactional
    public void bootstrap(String name, String hash) {
        gate();
        if (sql.jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) > 0)
            throw new LabException("OPERATION_CONFLICT", "系统已有用户，禁止再次初始化");
        sql.jdbc.update("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,'ADMIN',FALSE)", name, hash);
    }

    /**
     * 创建 USER，不能由客户端传角色。
     */
    @Transactional
    public UserSnapshot create(UserContext actor, String name, String hash) {
        gate();
        admin(actor);
        try {
            long id = sql.insert("INSERT INTO users(username,password_hash,role) VALUES(?,?,'USER')", name, hash);
            return user(id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw new LabException("OPERATION_CONFLICT", "用户名已存在");
        }
    }

    /**
     * 脱敏分页，管理员也不能获取密码。
     */
    public List<UserSnapshot> list(UserContext actor, int offset, int limit) {
        admin(actor);
        return sql.jdbc.query("SELECT * FROM users ORDER BY id LIMIT ? OFFSET ?", SqlSupport::user, limit, offset);
    }

    /**
     * 禁用、恢复和角色变更及时撤销所有登录。
     */
    @Transactional
    public UserSnapshot update(UserContext actor, long id, boolean enabled, UserContext.Role role) {
        gate();
        admin(actor);
        var rows = sql.jdbc.query("SELECT * FROM users WHERE id=? FOR UPDATE", SqlSupport::user, id);
        if (rows.isEmpty()) throw LabException.denied();
        var u = rows.get(0);
        if (u.role() == UserContext.Role.ADMIN && u.enabled() && (!enabled || role != UserContext.Role.ADMIN) && sql.jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND enabled=TRUE", Long.class) <= 1)
            throw new LabException("OPERATION_CONFLICT", "不能禁用或降级最后有效管理员");
        sql.jdbc.update("UPDATE users SET enabled=?,role=?,permission_version=permission_version+1 WHERE id=?", enabled, role.name(), id);
        sql.jdbc.update("UPDATE auth_tokens SET revoked=TRUE WHERE user_id=?", id);
        return user(id).orElseThrow();
    }

    /**
     * 首次改密允许临时密码身份，但仍核验当前版本与启用状态。
     */
    @Transactional
    public void changePassword(UserContext actor, String hash) {
        gate();
        var u = sql.jdbc.query("SELECT * FROM users WHERE id=? FOR UPDATE", SqlSupport::user, actor.userId()).stream().findFirst().orElseThrow(LabException::denied);
        if (!u.enabled() || u.permissionVersion() != actor.permissionVersion()) throw LabException.denied();
        sql.jdbc.update("UPDATE users SET password_hash=?,password_change_required=FALSE,permission_version=permission_version+1 WHERE id=?", hash, actor.userId());
        sql.jdbc.update("UPDATE auth_tokens SET revoked=TRUE WHERE user_id=?", actor.userId());
    }

    /**
     * 防止验证密码后并发禁用账户仍签发旧版本 token。
     */
    @Transactional
    public void issue(UserSnapshot expected, String hash, Instant expiry) {
        gate();
        var u = sql.jdbc.query("SELECT * FROM users WHERE id=? FOR UPDATE", SqlSupport::user, expected.id()).stream().findFirst().orElseThrow(LabException::denied);
        if (!u.enabled() || u.permissionVersion() != expected.permissionVersion()) throw LabException.denied();
        sql.jdbc.update("INSERT INTO auth_tokens(token_hash,user_id,permission_version,expires_at) VALUES(?,?,?,?)", hash, u.id(), u.permissionVersion(), Timestamp.from(expiry));
    }

    /**
     * 到期、撤销、账号启用与权限版本全部应用于本次请求。
     */
    public Optional<UserSnapshot> authenticate(String hash) {
        return sql.jdbc.query("SELECT u.* FROM auth_tokens t JOIN users u ON u.id=t.user_id WHERE t.token_hash=? AND t.revoked=FALSE AND t.expires_at>CURRENT_TIMESTAMP(6) AND u.enabled=TRUE AND u.permission_version=t.permission_version", SqlSupport::user, hash).stream().findFirst();
    }

    /**
     * 原 token 不进入日志或存储。
     */
    public void revoke(String hash) {
        sql.jdbc.update("UPDATE auth_tokens SET revoked=TRUE WHERE token_hash=?", hash);
    }

    /**
     * 统一串行控制首管理员与权限变更。
     */
    private void gate() {
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE", Integer.class);
    }

    /**
     * 数据层复核当前 ADMIN，拒绝伪造上下文。
     */
    private void admin(UserContext actor) {
        sql.actor(actor, false);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
    }
}
