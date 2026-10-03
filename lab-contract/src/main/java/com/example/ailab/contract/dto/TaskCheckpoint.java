package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * TaskCheckpoint 持久任务契约，不保存可执行 Java 对象。
 */
public record TaskCheckpoint(String stepId, String content, List<SourceDependency> sourceDependencies,
                             boolean partial) {
    /**
     * 防御性复制参数和来源列表。
     */
    public TaskCheckpoint {
        sourceDependencies = List.copyOf(sourceDependencies);
    }
}
