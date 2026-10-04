package com.example.ailab.contract.port;

import com.example.ailab.contract.context.TraceContext;

/** 可丢排错资料独立于可靠任务预算；缺装配时显式产生不完整链路。 */
public interface TraceTelemetryPort {
    TraceTelemetryPort NONE = (id, actor, session, task, ingestion) -> TraceContext.disabled(id);
    /** 身份和关联均由服务端入口传入，客户端trace不能获得权限。 */
    TraceContext open(String traceId, long actorUserId, Long sessionId, Long taskId, Long ingestionId);
}
