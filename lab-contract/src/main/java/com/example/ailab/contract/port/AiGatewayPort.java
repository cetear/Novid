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
    /** 按可信身份与任务查询工具白名单；旧演示实现不声明工具能力。 */
    default List<ToolDefinition> tools(UserContext actor, String taskType) { return List.of(); }
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

