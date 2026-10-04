package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.GovernanceStorePort;
import com.example.ailab.data.search.ElasticsearchRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** 可靠访问审计与有界运维；费用、任务、来源和原文不属于可丢排错数据。 */
@Repository
public class GovernanceRepository implements GovernanceStorePort {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final SqlSupport sql;
    private final ElasticsearchRepository search;

    /** 聚合只读取缓存计数，不读取缓存键或私人内容。 */
    public GovernanceRepository(SqlSupport sql, ElasticsearchRepository search) {
        this.sql = sql;
        this.search = search;
    }

    /** 管理读取在短事务内与角色撤销串行，最多一百条游标页。 */
    @Transactional(timeout = 5)
    public List<AccessAudit> audits(UserContext actor, long afterId, int limit) {
        admin(actor);
        if (afterId < 0 || limit < 1 || limit > 100) throw LabException.invalid("审计分页超限");
        return sql.jdbc.query("SELECT * FROM knowledge_access_audit WHERE id>? ORDER BY id LIMIT ?", (r, n) ->
                new AccessAudit(r.getLong("id"), r.getLong("actor_user_id"), r.getString("action"),
                        (Long) r.getObject("resource_id"), r.getString("scope_mode"),
                        (Long) r.getObject("permission_version"), (Long) r.getObject("knowledge_epoch"),
                        (Integer) r.getObject("result_count"), r.getString("outcome"), r.getTimestamp("created_at").toInstant(),
                        selectionIds(r.getString("scope_json")), selectionOwner(r.getString("scope_json")), ids(r.getString("resource_ids_json"))), afterId, limit);
    }

    /** 固定SQL汇总当前积压和有界窗口；不输出单用户／资源维度。 */
    @Transactional(timeout = 5)
    public OperationalMetrics metrics(UserContext actor, Instant since) {
        admin(actor);
        if (since == null || since.isBefore(Instant.now().minusSeconds(30 * 86400L + 60)) || since.isAfter(Instant.now()))
            throw LabException.invalid("指标窗口超限");
        var runs = sql.jdbc.queryForMap("SELECT COUNT(*) total,COALESCE(SUM(status NOT IN ('SUCCESS','RUNNING')),0) failed,COALESCE(SUM(incomplete),0) incomplete FROM ai_runs WHERE created_at>=?", Timestamp.from(since));
        var cache = search.cacheStatistics();
        return new OperationalMetrics(((Number) runs.get("total")).longValue(), ((Number) runs.get("failed")).longValue(),
                ((Number) runs.get("incomplete")).longValue(),
                count("SELECT COUNT(*) FROM ai_tasks WHERE status='QUEUED'"),
                count("SELECT COUNT(*) FROM outbox_events WHERE status IN ('PENDING','PROCESSING')"),
                sql.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_access_audit WHERE created_at>=?", Long.class, Timestamp.from(since)),
                cache[0], cache[1], cache[2]);
    }

    /** 每类一百行上限；同一短事务保证中途失败不留下半次清理，最少七天历史保留。 */
    @Transactional(timeout = 5)
    public RetentionResult purge(Instant expiredBefore, Instant historyBefore, int maximum) {
        Instant now = Instant.now();
        if (maximum < 1 || maximum > 100 || expiredBefore == null || expiredBefore.isAfter(now)
                || historyBefore == null || historyBefore.isAfter(now.minusSeconds(7 * 86400L)))
            throw LabException.invalid("维护截止时间或批次超限");
        // 与资料撤销／来源提交共用全局锁，避免检查引用后被另一个事务新引用。
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE", Integer.class);
        int tokens = sql.jdbc.update("DELETE FROM auth_tokens WHERE expires_at<? ORDER BY expires_at LIMIT ?", Timestamp.from(expiredBefore), maximum);
        // 到期的非终态任务去重仍保留；未知命名空间默认保留，不能缩短原至少七天保证。
        var dedup = sql.jdbc.query("SELECT actor_user_id,namespace,request_key FROM request_deduplications r WHERE expires_at<? AND (namespace IN ('DOCUMENT_CREATE','SESSION_CREATE') OR namespace='TASK_CREATE' AND EXISTS (SELECT 1 FROM ai_tasks t WHERE t.id=r.resource_id AND t.requester_user_id=r.actor_user_id AND t.status IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED'))) ORDER BY expires_at LIMIT ?",
                (r, n) -> new Object[]{r.getLong(1), r.getString(2), r.getString(3)}, Timestamp.from(expiredBefore), maximum);
        for (var key : dedup) sql.jdbc.update("DELETE FROM request_deduplications WHERE actor_user_id=? AND namespace=? AND request_key=?", key);
        int audit = sql.jdbc.update("DELETE FROM knowledge_access_audit WHERE created_at<? ORDER BY created_at,id LIMIT ?", Timestamp.from(historyBefore), maximum);
        int structure = pruneStructure(historyBefore, maximum);
        return new RetentionResult(tokens, dedup.size(), audit, structure);
    }

    /** 只清已成功且非当前激活的旧结构；保守按整篇文档引用保留，原文与执行事实永久不由此删除。 */
    private int pruneStructure(Instant before, int maximum) {
        String unreferenced = " AND NOT EXISTS(SELECT 1 FROM source_dependencies s WHERE s.source_document_id=i.document_id)"
                + " AND NOT EXISTS(SELECT 1 FROM task_document_coverage c WHERE c.document_id=i.document_id)"
                + " AND NOT EXISTS(SELECT 1 FROM ai_tasks t WHERE JSON_CONTAINS(t.request_json,CAST(i.document_id AS JSON),'$.documentIds'))";
        // JSON保存的来源也是真实引用；不限于仍活跃任务或会话，过期审批仍保守保留。
        for (String table : List.of("messages", "sessions", "approvals", "task_steps", "artifacts", "task_document_pages")) {
            String field = table.equals("sessions") ? "summary_source_json" : "source_json";
            unreferenced += " AND NOT EXISTS(SELECT 1 FROM " + table + " ref WHERE JSON_CONTAINS(ref." + field + ",JSON_OBJECT('documentId',i.document_id)))";
        }
        var rows = sql.jdbc.query("SELECT i.document_id,i.document_version,i.processing_revision FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN document_versions v ON v.document_id=i.document_id AND v.document_version=i.document_version WHERE i.status='READY' AND i.created_at<? AND (d.current_version<>i.document_version OR v.active_processing_revision IS NULL OR v.active_processing_revision<>i.processing_revision)"
                + unreferenced
                + " AND EXISTS(SELECT 1 FROM document_sections s WHERE s.document_id=i.document_id AND s.document_version=i.document_version AND s.processing_revision=i.processing_revision) ORDER BY i.created_at,i.id LIMIT 1",
                (r, n) -> new Object[]{r.getLong(1), r.getInt(2), r.getLong(3)}, Timestamp.from(before));
        if (rows.isEmpty()) return 0;
        var generation = rows.get(0);
        int removed = sql.jdbc.update("DELETE FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=? LIMIT ?", append(generation, maximum));
        if (removed < maximum) removed += sql.jdbc.update("DELETE FROM context_parents WHERE document_id=? AND document_version=? AND processing_revision=? AND NOT EXISTS(SELECT 1 FROM chunks c WHERE c.parent_id=context_parents.parent_id) LIMIT ?", append(generation, maximum - removed));
        if (removed < maximum) removed += sql.jdbc.update("DELETE FROM document_sections WHERE document_id=? AND document_version=? AND processing_revision=? AND NOT EXISTS(SELECT 1 FROM context_parents p WHERE p.section_id=document_sections.section_id) LIMIT ?", append(generation, maximum - removed));
        return removed;
    }

    /** 旧范围字段为空保持未知，新字段只解码有限的整数ID，不启用任意类型。 */
    private List<Long> selectionIds(String selection) {
        return selection == null ? null : ids(node(selection).path("knowledgeBaseIds").toString());
    }

    /** owner筛选只是范围缩小元数据，无筛选或旧未知均为null。 */
    private Long selectionOwner(String selection) {
        if (selection == null) return null;
        var value = node(selection).path("ownerUserId");
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) throw new IllegalStateException("审计范围损坏");
        return value.longValue();
    }

    /** 只读取上限一百项的正整数数组；损坏历史不能作为正文或脚本返回。 */
    private List<Long> ids(String value) {
        if (value == null) return null;
        var array = node(value);
        if (!array.isArray() || array.size() > 100) throw new IllegalStateException("审计对象集合损坏");
        var result = new java.util.ArrayList<Long>();
        for (var item : array) {
            if (!item.isIntegralNumber() || !item.canConvertToLong() || item.longValue() <= 0) throw new IllegalStateException("审计对象损坏");
            result.add(item.longValue());
        }
        return List.copyOf(result);
    }

    /** 固定JSON元数据复用序列化器，不启用多态类型或回传解析异常内容。 */
    private com.fasterxml.jackson.databind.JsonNode node(String value) {
        try { return JSON.readTree(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("审计元数据损坏"); }
    }

    /** 固定参数追加上限，SQL不接受用户提供的表名或表达式。 */
    private Object[] append(Object[] generation, int limit) { return new Object[]{generation[0], generation[1], generation[2], limit}; }

    /** 当前身份及角色在SQL侧再次核验，不能凭旧ADMIN上下文读管理资料。 */
    private void admin(UserContext actor) {
        sql.actor(actor, true);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
    }

    /** 固定聚合SQL不携带内容标签。 */
    private long count(String query) { return sql.jdbc.queryForObject(query, Long.class); }
}
