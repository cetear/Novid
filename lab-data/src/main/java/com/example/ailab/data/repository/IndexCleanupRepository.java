package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.IndexCleanupMapper;
import com.example.ailab.data.persistence.po.SqlRow;
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
    private final IndexCleanupMapper mapper;
    private final SqlSupport sql;

    /**
     * 使用已有 SQL 支持及一致锁序。
     */
    public IndexCleanupRepository(SqlSupport sql) {
        this.sql = sql; this.mapper = sql.mapper(IndexCleanupMapper.class);
    }

    /**
     * 执行权带 fencing，过期后可恢复；成功分批不消耗异常尝试预算。
     */
    @Transactional
    public Optional<IndexCleanupLease> claim(String worker) {
        sql.scalar(mapper.claimSystemControlSelect(new Object[]{}), Integer.class);
        mapper.claimOutboxEventsWrite(new Object[]{});
        var events = sql.project(mapper.claimOutboxEventsSelect(new Object[]{}), (r, n) -> new IndexCleanupLease(r.longValue("id"), r.string("event_type"), r.longValue("resource_id"), r.longValue("resource_version"), 0, 0, worker, r.longValue("fencing_token") + 1));
        if (events.isEmpty()) return Optional.empty();
        var event = events.get(0);
        // PRUNE 的边界来自已提交的 ingestion，不读取可变化的当前版本标签。
        if (event.eventType().equals("PRUNE_DOCUMENT")) {
            event = sql.one(sql.project(mapper.claimDocumentIngestionsSelect(new Object[]{event.resourceVersion()}), (r, n) -> new IndexCleanupLease(events.get(0).eventId(), "PRUNE_DOCUMENT", events.get(0).resourceId(), events.get(0).resourceVersion(), r.intValue(1), r.longValue(2), worker, events.get(0).fencingToken())));
        }
        mapper.claimOutboxEventsWrite2(new Object[]{worker, event.fencingToken(), event.eventId()});
        return Optional.of(event);
    }

    /**
     * CAS 只允许当前未过期的领取者提交；未删完就进入下一有界批次。
     */
    @Transactional
    public void finish(IndexCleanupLease lease, boolean complete) {
        mapper.finishOutboxEventsWrite(new Object[]{complete ? "DONE" : "PENDING", lease.eventId(), lease.workerId(), lease.fencingToken()});
    }

    /**
     * 外部结果不确定时不置 DONE；重试同一版本删除是幂等的。
     */
    @Transactional
    public void fail(IndexCleanupLease lease) {
        mapper.failOutboxEventsWrite(new Object[]{lease.eventId(), lease.workerId(), lease.fencingToken()});
    }
}
