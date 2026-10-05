package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.GovernanceMapper;
import com.example.ailab.data.persistence.po.SqlRow;
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
    private final GovernanceMapper mapper;
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private final SqlSupport sql;
    private final ElasticsearchRepository search;

    /** 聚合只读取缓存计数，不读取缓存键或私人内容。 */
    public GovernanceRepository(SqlSupport sql, ElasticsearchRepository search) {
        this.sql = sql; this.mapper = sql.mapper(GovernanceMapper.class);
        this.search = search;
    }

    /** 管理读取在短事务内与角色撤销串行，最多一百条游标页。 */
    @Transactional(timeout = 5)
    public List<AccessAudit> audits(UserContext actor, long afterId, int limit) {
        admin(actor);
        if (afterId < 0 || limit < 1 || limit > 100) throw LabException.invalid("审计分页超限");
        return sql.project(mapper.auditsKnowledgeAccessAuditSelect(new Object[]{afterId, limit}), (r, n) ->
                new AccessAudit(r.longValue("id"), r.longValue("actor_user_id"), r.string("action"),
                        (Long) r.value("resource_id"), r.string("scope_mode"),
                        (Long) r.value("permission_version"), (Long) r.value("knowledge_epoch"),
                        (Integer) r.value("result_count"), r.string("outcome"), r.timestamp("created_at").toInstant(),
                        selectionIds(r.string("scope_json")), selectionOwner(r.string("scope_json")), ids(r.string("resource_ids_json"))));
    }

    /** 固定SQL汇总当前积压和有界窗口；不输出单用户／资源维度。 */
    @Transactional(timeout = 5)
    public OperationalMetrics metrics(UserContext actor, Instant since) {
        admin(actor);
        if (since == null || since.isBefore(Instant.now().minusSeconds(30 * 86400L + 60)) || since.isAfter(Instant.now()))
            throw LabException.invalid("指标窗口超限");
        var runs = sql.one(mapper.metricsAiRunsSelect(new Object[]{Timestamp.from(since)}));
        var cache = search.cacheStatistics();
        return new OperationalMetrics(((Number) runs.get("total")).longValue(), ((Number) runs.get("failed")).longValue(),
                ((Number) runs.get("incomplete")).longValue(),
                sql.scalar(mapper.queuedTaskCount(), Long.class),
                sql.scalar(mapper.pendingEventCount(), Long.class),
                sql.scalar(mapper.metricsKnowledgeAccessAuditSelect(new Object[]{Timestamp.from(since)}), Long.class),
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
        sql.scalar(mapper.purgeSystemControlSelect(new Object[]{}), Integer.class);
        int tokens = mapper.purgeAuthTokensWrite(new Object[]{Timestamp.from(expiredBefore), maximum});
        // 到期的非终态任务去重仍保留；未知命名空间默认保留，不能缩短原至少七天保证。
        var dedup = sql.project(mapper.purgeRequestDeduplicationsSelect(new Object[]{Timestamp.from(expiredBefore), maximum}), (r, n) -> new Object[]{r.longValue(1), r.string(2), r.string(3)});
        for (var key : dedup) mapper.purgeRequestDeduplicationsWrite(key);
        int audit = mapper.purgeKnowledgeAccessAuditWrite(new Object[]{Timestamp.from(historyBefore), maximum});
        int structure = pruneStructure(historyBefore, maximum);
        return new RetentionResult(tokens, dedup.size(), audit, structure);
    }

    /** 只清已成功且非当前激活的旧结构；保守按整篇文档引用保留，原文与执行事实永久不由此删除。 */
    private int pruneStructure(Instant before, int maximum) {
        var rows = sql.project(mapper.pruneStructureDocumentIngestionsSelect(new Object[]{Timestamp.from(before)}), (r, n) -> new Object[]{r.longValue(1), r.intValue(2), r.longValue(3)});
        if (rows.isEmpty()) return 0;
        var generation = rows.get(0);
        int removed = mapper.pruneStructureChunksWrite(append(generation, maximum));
        if (removed < maximum) removed += mapper.pruneStructureContextParentsWrite(append(generation, maximum - removed));
        if (removed < maximum) removed += mapper.pruneStructureDocumentSectionsWrite(append(generation, maximum - removed));
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

}
