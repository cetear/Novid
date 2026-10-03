package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * UserStorePort 窄端口，实现由运行入口装配。
 */
public interface UserStorePort {
    /**
     * 按名称读取登录凭据，仅用于账户用例。
     */
    Optional<AccountCredential> credential(String username);

    /**
     * 按 ID 读取当前账户状态，不返回密码。
     */
    Optional<UserSnapshot> user(long id);

    /**
     * 仅在空系统中原子创建第一个管理员。
     */
    void bootstrap(String username, String passwordHash);

    /**
     * 创建普通账户，在事务内复核管理员身份。
     */
    UserSnapshot create(UserContext actor, String username, String passwordHash);

    /**
     * 分页返回脱敏账户。
     */
    List<UserSnapshot> list(UserContext actor, int offset, int limit);

    /**
     * 更新账户并撤销登录，保护最后有效管理员。
     */
    UserSnapshot update(UserContext actor, long id, boolean enabled, UserContext.Role role);

    /**
     * 按当前权限版本改密并撤销所有登录。
     */
    void changePassword(UserContext actor, String passwordHash);
}

