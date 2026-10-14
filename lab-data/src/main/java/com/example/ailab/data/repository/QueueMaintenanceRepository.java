package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 队列维护与领取分开提交；每类最多16个ID，锁繁忙的行留到下轮。 */
@Repository
public class QueueMaintenanceRepository {
    private final SqlSupport sql;
    public QueueMaintenanceRepository(SqlSupport sql) { this.sql=sql; }

    @Transactional(timeout=3, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void tasks() {
        var mapper=sql.mapper(TaskMapper.class);
        if(mapper.claimInternalSystemControlSelect(new Object[]{}).isEmpty())return;
        for(var row:mapper.cancelCandidates(new Object[]{})) {
            var id=new Object[]{row.longValue(1)};
            mapper.claimInternalAiTasksWrite(id);
            mapper.claimInternalTaskStepProgressWrite(id);
        }
        for(var row:mapper.failCandidates(new Object[]{})) {
            var id=new Object[]{row.longValue(1)};
            mapper.claimInternalAiTasksWrite2(id);
            mapper.claimInternalTaskStepProgressWrite2(id);
        }
    }

    @Transactional(timeout=3, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void ingestion() {
        var mapper=sql.mapper(IngestionMapper.class);
        if(mapper.claimSystemControlSelect(new Object[]{}).isEmpty())return;
        for(var row:mapper.unknownCandidates(new Object[]{})) {
            var id=new Object[]{row.longValue(1)};
            mapper.claimDocumentIngestionsWrite(id);
            mapper.claimIngestionBatchesWrite(id);
        }
        for(var row:mapper.exhaustedCandidates(new Object[]{}))mapper.claimDocumentIngestionsWrite2(new Object[]{row.longValue(1)});
    }

    @Transactional(timeout=3, isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cleanup() {
        var mapper=sql.mapper(IndexCleanupMapper.class);
        if(mapper.claimSystemControlSelect(new Object[]{}).isEmpty())return;
        for(var row:mapper.exhaustedCandidates(new Object[]{}))mapper.claimOutboxEventsWrite(new Object[]{row.longValue(1)});
    }
}
