package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * TaskRequest 持久任务契约，不保存可执行 Java 对象。
 */
public record TaskRequest(String taskType, String topic, ScopeRequest scope, List<Long> documentIds,
                          String idempotencyKey, String strategy, Media.PresentationOptions presentationOptions,
                          Media.VideoOptions videoOptions) {
    /** 保留旧六参数构造及FIXED请求的幂等语义。 */
    public TaskRequest(String taskType, String topic, ScopeRequest scope, List<Long> documentIds, String key, String strategy) {
        this(taskType, topic, scope, documentIds, key, strategy, null, null);
    }
    /** 旧任务保持FIXED策略；存量JSON没有strategy时也如此处理。 */
    public TaskRequest(String taskType, String topic, ScopeRequest scope, List<Long> documentIds, String idempotencyKey) {
        this(taskType, topic, scope, documentIds, idempotencyKey, null);
    }
    /**
     * 防御性复制参数和来源列表。
     */
    public TaskRequest {
        documentIds = List.copyOf(documentIds);
        strategy = strategy == null ? "FIXED" : strategy;
    }
}
