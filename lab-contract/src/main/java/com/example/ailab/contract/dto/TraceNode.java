package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

/** 类型化排错事实及有界私人快照；正文不进入外部遥测或异常消息。 */
public record TraceNode(String spanId, String parentSpanId, String type, String name, String stepId,
        String agentId, List<String> dependsOn, int sequence, String status, Instant startedAt, Instant endedAt,
        String errorCode, String modelId, String taskType, String profile, String policyVersion,
        String routeReason, int attempt, Integer inputTokens, Integer outputTokens, String usageSource,
        String toolCallHash, TracePayload input, TracePayload output, List<SourceDependency> payloadSources) {
    /** 依赖是实际已创建的节点ID，禁止外部修改并伪造汇合。 */
    public TraceNode {
        dependsOn = List.copyOf(dependsOn);
        payloadSources = payloadSources == null ? List.of() : List.copyOf(payloadSources);
    }
    /** 保留历史元数据构造方式，旧JSON缺少快照时保持未记录。 */
    public TraceNode(String spanId, String parentSpanId, String type, String name, String stepId,
            String agentId, List<String> dependsOn, int sequence, String status, Instant startedAt, Instant endedAt,
            String errorCode, String modelId, String taskType, String profile, String policyVersion,
            String routeReason, int attempt, Integer inputTokens, Integer outputTokens, String usageSource, String toolCallHash) {
        this(spanId, parentSpanId, type, name, stepId, agentId, dependsOn, sequence, status, startedAt, endedAt,
                errorCode, modelId, taskType, profile, policyVersion, routeReason, attempt, inputTokens, outputTokens,
                usageSource, toolCallHash, null, null, List.of());
    }
    /** 来源复核失败只隐藏内容，仍保留真实状态和耗时。 */
    public TraceNode payloads(TracePayload input, TracePayload output, List<SourceDependency> sources) {
        return new TraceNode(spanId, parentSpanId, type, name, stepId, agentId, dependsOn, sequence, status, startedAt,
                endedAt, errorCode, modelId, taskType, profile, policyVersion, routeReason, attempt, inputTokens,
                outputTokens, usageSource, toolCallHash, input, output, sources);
    }
}
