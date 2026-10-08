package com.example.ailab.business.application;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;

import java.security.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/**
 * 正式认证；模型 Mock 模式不会改变身份认证方式。
 */
@Service
public class AccountApplicationService {
    private final UserStorePort users;
    private final AuthTokenStorePort tokens;
    private final PasswordHashPort passwords;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;
    private final LoginRateLimiter attempts = new LoginRateLimiter();
    private final java.util.concurrent.Semaphore passwordChecks = new java.util.concurrent.Semaphore(4);

    /**
     * 注入凭证组件，未知用户也执行相同密码哈希验证。
     */
    public AccountApplicationService(UserStorePort users, AuthTokenStorePort tokens, PasswordHashPort passwords) {
        this.users = users;
        this.tokens = tokens;
        this.passwords = passwords;
        dummyHash = passwords.hash(secret());
    }

    /**
     * 有界账号窗口限流，达到容量拒绝新增名称。
     */
    private void limit(String name) {
        attempts.acquire(digest(name));
    }

    /**
     * 四个并发密码验证许可；压力拒绝不堆积无界等待线程，finally归还许可。
     */
    private boolean checkPassword(String password, String hash) {
        if (!passwordChecks.tryAcquire()) throw new LabException("RATE_LIMITED", "登录繁忙");
        try {
            return passwords.matches(password, hash);
        } finally {
            passwordChecks.release();
        }
    }

    /**
     * 验证密码后签发 256 位随机 token，存储只接受哈希。
     */
    public LoginResult login(String username, String password) {
        username(username);
        if (password == null || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw LabException.invalid("密码超出限制");
        limit(username.toLowerCase(Locale.ROOT));
        var c = users.credential(username);
        boolean valid = checkPassword(password, c.map(AccountCredential::passwordHash).orElse(dummyHash));
        if (!valid || c.isEmpty() || !c.get().user().enabled())
            throw new LabException("AUTH_REQUIRED", "用户名或密码不正确");
        String token = secret();
        Instant expiry = Instant.now().plusSeconds(28800);
        tokens.issue(c.get().user(), digest(token), expiry);
        return new LoginResult(token, expiry, c.get().user());
    }

    /**
     * 每请求读取当前用户、登录到期与撤销状态。
     */
    public UserContext authenticate(String token) {
        if (token == null || token.length() < 32 || token.length() > 128)
            throw new LabException("AUTH_REQUIRED", "登录已失效");
        var u = tokens.authenticate(digest(token)).orElseThrow(() -> new LabException("AUTH_REQUIRED", "登录已失效"));
        return new UserContext(u.id(), u.role(), u.enabled(), u.permissionVersion(), u.passwordChangeRequired());
    }

    /**
     * 撤销当前登录。
     */
    public void logout(String token) {
        tokens.revoke(digest(token));
    }

    /**
     * 返回脱敏当前账户。
     */
    public UserSnapshot me(UserContext actor) {
        return users.user(actor.userId()).orElseThrow(LabException::denied);
    }

    /**
     * 校验旧密码后原子改密、提升权限版本并撤销全部登录。
     */
    public void changePassword(UserContext actor, String oldPassword, String newPassword) {
        var c = users.credential(me(actor).username()).orElseThrow(LabException::denied);
        if (oldPassword == null || oldPassword.getBytes(StandardCharsets.UTF_8).length > 72 || !passwords.matches(oldPassword, c.passwordHash()))
            throw new LabException("AUTH_REQUIRED", "原密码不正确");
        password(newPassword);
        users.changePassword(actor, passwords.hash(newPassword));
    }

    /**
     * 仅显式空系统初始化使用，不保存默认密码。
     */
    public void bootstrap(String name, String raw) {
        username(name);
        password(raw);
        users.bootstrap(name, passwords.hash(raw));
    }

    /**
     * 管理员创建普通用户，只返回一次临时密码。
     */
    public CreatedUser create(UserContext actor, String name) {
        admin(actor);
        username(name);
        String raw = secret();
        return new CreatedUser(users.create(actor, name, passwords.hash(raw)), raw);
    }

    /**
     * 分页脱敏账户列表。
     */
    public List<UserSnapshot> list(UserContext actor, int page, int size) {
        admin(actor);
        page(page, size);
        return users.list(actor, page * size, size);
    }

    /**
     * 权限变更在短事务中保护最后有效管理员。
     */
    public UserSnapshot update(UserContext actor, long id, boolean enabled, UserContext.Role role) {
        admin(actor);
        if (role == null) throw LabException.invalid("角色不能为空");
        return users.update(actor, id, enabled, role);
    }

    /**
     * 重新核验数据库 ADMIN，不能相信客户端角色。
     */
    private void admin(UserContext actor) {
        var u = me(actor);
        if (!u.enabled() || u.passwordChangeRequired() || u.permissionVersion() != actor.permissionVersion() || u.role() != UserContext.Role.ADMIN)
            throw LabException.denied();
    }

    /**
     * 有限分页，避免无界读取。
     */
    public static void page(int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100) throw LabException.invalid("分页超出限制");
    }

    /**
     * 控制账号格式。
     */
    private void username(String s) {
        if (s == null || !s.matches("[A-Za-z0-9_.-]{3,64}"))
            throw LabException.invalid("用户名须为 3～64 个字母、数字或 _.-");
    }

    /**
     * BCrypt 字节限额显式核验，避免截断密码。
     */
    private void password(String s) {
        if (s == null || s.length() < 12 || s.length() > 64 || s.getBytes(StandardCharsets.UTF_8).length > 72)
            throw LabException.invalid("密码须为 12～64 字符且不超过 72 UTF-8 字节");
    }

    /**
     * 原始随机凭证只向签发请求返回。
     */
    private String secret() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * 不可逆哈希用于 token 与规范化请求摘要。
     */
    public static String digest(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
