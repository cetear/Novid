package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

/**
 * 完整会话事件；工具标识保留契约，S01 只提交成功问答对。
 */
public record SessionMessage(long seq, String role, String status, String content,
                             List<SourceDependency> sourceDependencies, List<SessionSource> sourceReferences,
                             String toolCallId, String toolName, Instant createdAt, ScopeRequest scope) {
    /**
     * 固定来源集合，禁止读取后修改历史授权事实。
     */
    public SessionMessage {
        sourceDependencies = sourceDependencies == null ? List.of() : List.copyOf(sourceDependencies);
        sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
    }
}
