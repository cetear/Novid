package com.example.ailab.contract.dto;

import java.time.Instant;

/**
 * 本人会话的可公开快照，不包含执行权和消息正文。
 */
public record SessionSnapshot(long id, String title, long version, ScopeRequest scope,
                              Instant createdAt, Instant updatedAt) {
}
