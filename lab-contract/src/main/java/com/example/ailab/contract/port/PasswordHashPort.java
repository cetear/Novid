package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * PasswordHashPort 窄端口，实现由运行入口装配。
 */
public interface PasswordHashPort {
    /**
     * 单向计算密码哈希。
     */
    String hash(String password);

    /**
     * 恒定策略核验密码与已保存哈希。
     */
    boolean matches(String password, String hash);
}

