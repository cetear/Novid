package com.example.ailab.contract.dto;

import java.time.Instant;

/**
 * 本人入库可靠进度；估计、提供方已知用量与未知尝试分开，不公开向量、输入或执行者。
 */
public record IngestionProgress(String phase, String failureStage, int claims, int modelAttempts,
                                long reservedInputTokens, long actualInputTokens, int unknownUsageAttempts,
                                Instant deadline,
                                Instant nextAttemptAt, boolean retryable, int plannedBatches, int embeddedBatches,
                                int indexedBatches,
                                int unknownBatches, int peakVectorItems) {
}
