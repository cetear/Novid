package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.TraceSnapshot;
import com.example.ailab.data.persistence.mapper.PrivateResourceMapper;
import com.example.ailab.data.persistence.po.TraceSpanRow;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.List;

/** 图节点已编码后才借连接；运行终态、迟到写保护和节点仍原子提交。 */
@Repository
public class TraceGraphTransactions {
    private final PrivateResourceMapper mapper;
    public TraceGraphTransactions(SqlSupport sql) { mapper=sql.mapper(PrivateResourceMapper.class); }
    @Transactional
    public void commit(TraceSnapshot run,List<TraceSpanRow> rows) {
        mapper.recordAiRunsWrite(new Object[]{run.traceId(),run.actorUserId(),run.status(),run.modelId(),run.attempts(),run.mock(),Timestamp.from(run.createdAt()),run.endedAt()==null?null:Timestamp.from(run.endedAt()),run.sessionId(),run.taskId(),run.ingestionId(),run.previousTraceId(),run.incomplete(),run.telemetryDropped(),run.nodeCount()});
        if(!mapper.graphRunAccepts(new Object[]{run.traceId(),run.actorUserId(),run.endedAt()==null?null:Timestamp.from(run.endedAt())}))return;
        if(!rows.isEmpty())mapper.recordGraphAiSpansWrite(rows);
    }
}
