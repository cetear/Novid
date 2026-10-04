package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

/** 排错节点只保存类型化元数据；没有正文、参数、提示词或异常消息入口。 */
public record TraceNode(String spanId, String parentSpanId, String type, String name, String stepId,
        String agentId, List<String> dependsOn, int sequence, String status, Instant startedAt, Instant endedAt,
        String errorCode, String modelId, String taskType, String profile, String policyVersion,
        String routeReason, int attempt, Integer inputTokens, Integer outputTokens, String usageSource,
        String toolCallHash) {
    /** 依赖是实际已创建的节点ID，禁止外部修改并伪造汇合。 */
    public TraceNode { dependsOn = List.copyOf(dependsOn); }
}
