package com.example.ailab.contract.dto;
import java.time.Instant;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** TraceSnapshot 的跨模块不可变快照，不包含 ORM 对象。 */
public record TraceSnapshot(String traceId, long actorUserId, String status, String modelId, int attempts, boolean mock, Instant createdAt) {
}

