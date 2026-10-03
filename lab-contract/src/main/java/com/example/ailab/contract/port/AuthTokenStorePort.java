package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * AuthTokenStorePort 窄端口，实现由运行入口装配。
 */
public interface AuthTokenStorePort {
    /**
     * 只保存高强度 token 的哈希，并复核用户权限版本。
     */
    void issue(UserSnapshot user, String tokenHash, Instant expiresAt);

    /**
     * 每请求复核 token、到期、撤销、当前用户状态及权限版本。
     */
    Optional<UserSnapshot> authenticate(String tokenHash);

    /**
     * 撤销当前登录。
     */
    void revoke(String tokenHash);
}

