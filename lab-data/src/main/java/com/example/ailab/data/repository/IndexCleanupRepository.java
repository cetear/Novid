package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.IndexCleanupLease;
import com.example.ailab.contract.port.IndexCleanupStorePort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 重用已有 Outbox；短事务领取与提交，绝不在持锁期间调用 ES。
 */
@Repository
public class IndexCleanupRepository implements IndexCleanupStorePort {
    private final SqlSupport sql;

    /**
     * 使用已有 SQL 支持及一致锁序。
     */
    public IndexCleanupRepository(SqlSupport sql) {
        this.sql = sql;
    }

    /**
     * 执行权带 fencing，过期后可恢复；成功分批不消耗异常尝试预算。
     */
    @Transactional
    public Optional<IndexCleanupLease> claim(String worker) {
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE", Integer.class);
        sql.jdbc.update("UPDATE outbox_events SET status='FAILED',worker_id=NULL,lease_until=NULL,fencing_token=fencing_token+1 WHERE event_type IN ('DELETE_DOCUMENT','DELETE_BASE','PRUNE_DOCUMENT') AND attempt>=3 AND (status='PENDING' OR status='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6))");
        var events = sql.jdbc.query("SELECT e.* FROM outbox_events e WHERE e.event_type IN ('DELETE_DOCUMENT','DELETE_BASE','PRUNE_DOCUMENT') AND e.attempt<3 AND e.next_attempt_at<=CURRENT_TIMESTAMP(6) AND (e.status='PENDING' OR e.status='PROCESSING' AND e.lease_until<CURRENT_TIMESTAMP(6)) AND (e.event_type='DELETE_DOCUMENT' AND EXISTS(SELECT 1 FROM documents d WHERE d.id=e.resource_id AND d.deleted=TRUE) OR e.event_type='DELETE_BASE' AND EXISTS(SELECT 1 FROM knowledge_bases k WHERE k.id=e.resource_id AND k.deleted=TRUE) OR e.event_type='PRUNE_DOCUMENT' AND EXISTS(SELECT 1 FROM document_ingestions i WHERE i.id=e.resource_version AND i.document_id=e.resource_id AND i.status='READY')) ORDER BY e.id LIMIT 1 FOR UPDATE SKIP LOCKED",
                (r, n) -> new IndexCleanupLease(r.getLong("id"), r.getString("event_type"), r.getLong("resource_id"), r.getLong("resource_version"), 0, 0, worker, r.getLong("fencing_token") + 1));
        if (events.isEmpty()) return Optional.empty();
        var event = events.get(0);
        // PRUNE 的边界来自已提交的 ingestion，不读取可变化的当前版本标签。
        if (event.eventType().equals("PRUNE_DOCUMENT")) {
            event = sql.jdbc.queryForObject("SELECT document_version,processing_revision FROM document_ingestions WHERE id=?",
                    (r, n) -> new IndexCleanupLease(events.get(0).eventId(), "PRUNE_DOCUMENT", events.get(0).resourceId(), events.get(0).resourceVersion(), r.getInt(1), r.getLong(2), worker, events.get(0).fencingToken()), event.resourceVersion());
        }
        sql.jdbc.update("UPDATE outbox_events SET attempt=attempt+IF(status='PROCESSING',1,0),status='PROCESSING',worker_id=?,fencing_token=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 90 SECOND) WHERE id=?", worker, event.fencingToken(), event.eventId());
        return Optional.of(event);
    }

    /**
     * CAS 只允许当前未过期的领取者提交；未删完就进入下一有界批次。
     */
    @Transactional
    public void finish(IndexCleanupLease lease, boolean complete) {
        sql.jdbc.update("UPDATE outbox_events SET status=?,worker_id=NULL,lease_until=NULL,next_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=? AND worker_id=? AND fencing_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP(6)", complete ? "DONE" : "PENDING", lease.eventId(), lease.workerId(), lease.fencingToken());
    }

    /**
     * 外部结果不确定时不置 DONE；重试同一版本删除是幂等的。
     */
    @Transactional
    public void fail(IndexCleanupLease lease) {
        sql.jdbc.update("UPDATE outbox_events SET attempt=attempt+1,status=IF(attempt>=3,'FAILED','PENDING'),worker_id=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE id=? AND worker_id=? AND fencing_token=? AND status='PROCESSING' AND lease_until>CURRENT_TIMESTAMP(6)", lease.eventId(), lease.workerId(), lease.fencingToken());
    }
}
