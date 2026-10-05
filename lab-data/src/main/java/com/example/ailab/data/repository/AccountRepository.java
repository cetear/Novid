package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.AccountMapper;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.mapper.UserMapper;
import com.example.ailab.data.persistence.mapper.AuthTokenMapper;
import com.example.ailab.data.persistence.po.UserPo;
import com.example.ailab.data.persistence.po.AuthTokenPo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.*;

/**
 * 账户与 token 权威存储。所有管理变更先锁控制行，避免最后 ADMIN 竞态。
 */
@Repository
public class AccountRepository implements UserStorePort, AuthTokenStorePort {
    private final AccountMapper mapper;
    private final SqlSupport sql;
    private final UserMapper users;
    private final AuthTokenMapper tokens;

    /**
     * Mapper 由 Spring 先完成注册再注入；关键事务使用同一数据源显式 SQL。
     */
    public AccountRepository(SqlSupport sql, UserMapper mapper, AuthTokenMapper tokens) {
        this.sql = sql; this.mapper = sql.mapper(AccountMapper.class);
        this.users = mapper;
        this.tokens = tokens;
    }

    /**
     * 登录凭据仅向账户用例返回。
     */
    public Optional<AccountCredential> credential(String name) {
        return Optional.ofNullable(users.selectOne(new QueryWrapper<UserPo>().eq("username", name)))
                .map(p -> new AccountCredential(p.snapshot(), p.passwordHash));
    }

    /**
     * 将内部 PO 转换为无凭证契约。
     */
    public Optional<UserSnapshot> user(long id) {
        var p = users.selectById(id);
        return Optional.ofNullable(p).map(UserPo::snapshot);
    }

    /**
     * 空系统初始化串行化，已有账户明确拒绝。
     */
    @Transactional
    public void bootstrap(String name, String hash) {
        gate();
        if (users.selectCount(null) > 0)
            throw new LabException("OPERATION_CONFLICT", "系统已有用户，禁止再次初始化");
        var user = new UserPo();
        user.username = name; user.passwordHash = hash; user.role = "ADMIN"; user.passwordChangeRequired = false;
        users.insert(user);
    }

    /**
     * 创建 USER，不能由客户端传角色。
     */
    @Transactional
    public UserSnapshot create(UserContext actor, String name, String hash) {
        gate();
        admin(actor);
        try {
            var user = new UserPo();
            user.username = name; user.passwordHash = hash; user.role = "USER";
            users.insert(user);
            return user(user.id).orElseThrow();
        } catch (DuplicateKeyException e) {
            throw new LabException("OPERATION_CONFLICT", "用户名已存在");
        }
    }

    /**
     * 脱敏分页，管理员也不能获取密码。
     */
    public List<UserSnapshot> list(UserContext actor, int offset, int limit) {
        admin(actor);
        return users.selectPageUsers(offset, limit).stream().map(UserPo::snapshot).toList();
    }

    /**
     * 禁用、恢复和角色变更及时撤销所有登录。
     */
    @Transactional
    public UserSnapshot update(UserContext actor, long id, boolean enabled, UserContext.Role role) {
        gate();
        admin(actor);
        var u = Optional.ofNullable(users.selectCurrent(id, true)).map(UserPo::snapshot).orElseThrow(LabException::denied);
        if (u.role() == UserContext.Role.ADMIN && u.enabled() && (!enabled || role != UserContext.Role.ADMIN)
                && users.selectCount(new QueryWrapper<UserPo>().eq("role", "ADMIN").eq("enabled", true)) <= 1)
            throw new LabException("OPERATION_CONFLICT", "不能禁用或降级最后有效管理员");
        users.update(null, new UpdateWrapper<UserPo>().eq("id", id).set("enabled", enabled).set("role", role.name())
                .setSql("permission_version=permission_version+1"));
        revokeUserTokens(id);
        return user(id).orElseThrow();
    }

    /**
     * 首次改密允许临时密码身份，但仍核验当前版本与启用状态。
     */
    @Transactional
    public void changePassword(UserContext actor, String hash) {
        gate();
        var u = Optional.ofNullable(users.selectCurrent(actor.userId(), true)).map(UserPo::snapshot).orElseThrow(LabException::denied);
        if (!u.enabled() || u.permissionVersion() != actor.permissionVersion()) throw LabException.denied();
        users.update(null, new UpdateWrapper<UserPo>().eq("id", actor.userId()).set("password_hash", hash)
                .set("password_change_required", false).setSql("permission_version=permission_version+1"));
        revokeUserTokens(actor.userId());
    }

    /**
     * 防止验证密码后并发禁用账户仍签发旧版本 token。
     */
    @Transactional
    public void issue(UserSnapshot expected, String hash, Instant expiry) {
        gate();
        var u = Optional.ofNullable(users.selectCurrent(expected.id(), true)).map(UserPo::snapshot).orElseThrow(LabException::denied);
        if (!u.enabled() || u.permissionVersion() != expected.permissionVersion()) throw LabException.denied();
        var token = new AuthTokenPo();
        token.tokenHash = hash; token.userId = u.id(); token.permissionVersion = u.permissionVersion(); token.expiresAt = expiry;
        tokens.insert(token);
    }

    /**
     * 到期、撤销、账号启用与权限版本全部应用于本次请求。
     */
    public Optional<UserSnapshot> authenticate(String hash) {
        return Optional.ofNullable(users.selectAuthenticated(hash)).map(UserPo::snapshot);
    }

    /**
     * 原 token 不进入日志或存储。
     */
    public void revoke(String hash) {
        tokens.update(null, new UpdateWrapper<AuthTokenPo>().eq("token_hash", hash).set("revoked", true));
    }

    private void revokeUserTokens(long id) {
        tokens.update(null, new UpdateWrapper<AuthTokenPo>().eq("user_id", id).set("revoked", true));
    }

    /**
     * 统一串行控制首管理员与权限变更。
     */
    private void gate() {
        sql.scalar(mapper.gateSystemControlSelect(new Object[]{}), Integer.class);
    }

    /**
     * 数据层复核当前 ADMIN，拒绝伪造上下文。
     */
    private void admin(UserContext actor) {
        sql.actor(actor, false);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
    }
}
