package com.example.ailab.contract.dto;

import java.time.Instant;

/**
 * 首次执行确定的绝对期限与跨领取累计预算，不将重启当作新执行。
 */
public record IngestionBudget(Instant deadline, int attempts, long reservedInputTokens) {
}
