package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * TraceSnapshot 的跨模块不可变快照，不包含 ORM 对象。
 */
public record TraceSnapshot(String traceId, long actorUserId, String status, String modelId, int attempts, boolean mock,
                            Instant createdAt, Instant endedAt, Long sessionId, Long taskId, Long ingestionId,
                            String previousTraceId, boolean incomplete, boolean telemetryDropped, int nodeCount,
                            Instant firstDeliverableAt) {
    /** 内部节点写入不覆盖HTTP首次放行时间，由传输层独立记录。 */
    public TraceSnapshot(String traceId,long actorUserId,String status,String modelId,int attempts,boolean mock,Instant createdAt,
            Instant endedAt,Long sessionId,Long taskId,Long ingestionId,String previousTraceId,boolean incomplete,boolean telemetryDropped,int nodeCount) {
        this(traceId,actorUserId,status,modelId,attempts,mock,createdAt,endedAt,sessionId,taskId,ingestionId,previousTraceId,incomplete,telemetryDropped,nodeCount,null);
    }
    /** 旧摘要没有节点证据，兼容构造明确标不完整。 */
    public TraceSnapshot(String traceId,long actorUserId,String status,String modelId,int attempts,boolean mock,Instant createdAt) {
        this(traceId,actorUserId,status,modelId,attempts,mock,createdAt,null,null,null,null,null,true,false,0);
    }
}

