package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * AiGatewayPort 窄端口，实现由运行入口装配。
 */
public interface AiGatewayPort {
    /**
     * 固定工作流入口，模型／工具／结果校验必须经过统一控制。
     */
    AiResult answer(UserContext actor, AiRequest request);
}

