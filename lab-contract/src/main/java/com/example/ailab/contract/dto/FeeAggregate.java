package com.example.ailab.contract.dto;

import java.math.BigDecimal;

/**
 * 管理聚合仅按服务端计价币种分组，不暴露用户、任务、正文或操作键。
 */
public record FeeAggregate(String currency, int attempts, int unknownAttempts, int pendingAttempts,
                           int simulatedAttempts, BigDecimal estimatedAmount, BigDecimal reservedAmount) {
}
