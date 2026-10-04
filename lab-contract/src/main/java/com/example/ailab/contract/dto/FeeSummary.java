package com.example.ailab.contract.dto;

import java.math.BigDecimal;

/** 已定价金额仍是估算而非供应商账单；未知及未落账历史独立列出。 */
public record FeeSummary(String kind, String resourceId, String currency, BigDecimal limitAmount,
                         BigDecimal estimatedAmount, BigDecimal reservedAmount, long providerInputTokens,
                         long providerOutputTokens, long reservedTokens, int attempts, int settledAttempts,
                         int unknownAttempts, int pendingAttempts, int simulatedAttempts, int legacyUntrackedAttempts,
                         boolean overLimit, String costStatus) { }
