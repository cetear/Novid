package com.example.ailab.contract.dto;

import java.time.Instant;

/** 用户可见的步骤事实；不返回模型草稿、提示词、思维链或内部执行者标识。 */
public record TaskStepSnapshot(String stepId, String label, String status, Instant startedAt,
                               Instant completedAt, String errorCode) {
}
