package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.context.RequestCancellation;

/**
 * AiGatewayPort 窄端口，实现由运行入口装配。
 */
public interface AiGatewayPort {
    /**
     * 固定工作流入口，模型／工具／结果校验必须经过统一控制。
     */
    AiResult answer(UserContext actor, AiRequest request);
    /** 支持可取消在线请求；已有显式演示实现仍使用旧入口。 */
    default AiResult answer(UserContext actor, AiRequest request, RequestCancellation cancellation) {
        cancellation.check();
        return answer(actor, request);
    }
}

