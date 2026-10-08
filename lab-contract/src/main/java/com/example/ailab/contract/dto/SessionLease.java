package com.example.ailab.contract.dto;

import java.time.Instant;

/**
 * 远程调用期间持有的不可变执行权；数据库事务在领取时已经结束。
 */
public record SessionLease(long sessionId, String executionId, long version, Instant leaseUntil,
                           ScopeRequest scope, long contextFloorSeq) {
}
