package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.SqlSupportMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.data.persistence.po.SqlParameters;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * data 内部 SQL 与授权条件辅助；不向其他模块暴露 SQL 或 Wrapper。
 */
@Component
public class SqlSupport {
    private final SqlSupportMapper mapper;
    private final org.mybatis.spring.SqlSessionTemplate session;
    public SqlSupport(org.mybatis.spring.SqlSessionTemplate session) {
        this.session = session;
        this.mapper = session.getMapper(SqlSupportMapper.class);
    }
    public <T> T mapper(Class<T> type) { return session.getMapper(type); }

    /**
     * user 行锁与权限版本核验，写入以数据库当前事实为准。
     */
    public void actor(UserContext actor, boolean lock) {
        if (lock) scalar(mapper.actorSystemControlSelect(new Object[]{}), Integer.class);
        var row = mapper(com.example.ailab.data.persistence.mapper.UserMapper.class).selectCurrent(actor.userId(), lock);
        if (row == null) throw LabException.denied();
        var u = row.snapshot();
        if (!u.enabled() || u.role() != actor.role() || u.permissionVersion() != actor.permissionVersion() || u.passwordChangeRequired())
            throw LabException.denied();
    }

    /**
     * owner 写校验；使用 actor → base 的锁顺序。
     */
    public KnowledgeBaseSnapshot owner(UserContext actor, long id, boolean enabled) {
        actor(actor, true);
        var row = mapper(com.example.ailab.data.persistence.mapper.KnowledgeBaseMapper.class).selectLocked(id);
        if (row == null) throw LabException.denied();
        var k = row.snapshot();
        if (k.deleted() || k.ownerUserId() != actor.userId() || enabled && !k.enabled()) throw LabException.denied();
        return k;
    }

    /**
     * 构建启用／删除／本人／选择范围条件；空列表永远不是 ALL。
     */
    public void scope(AuthorizedKnowledgeScope scope, SqlParameters p) {
        actor(scope.actor(), false);
        p.addValue("actor", scope.actor().userId());
        if (scope.actor().role() != UserContext.Role.ADMIN && scope.mode() == ScopeRequest.Mode.ALL)
            throw LabException.denied();
        p.addValue("selfOnly", scope.actor().role() != UserContext.Role.ADMIN || scope.mode() == ScopeRequest.Mode.SELF);
        p.addValue("selected", scope.mode() == ScopeRequest.Mode.SELECTED);
        p.addValue("ids", scope.knowledgeBaseIds());
        p.addValue("regularUser", scope.actor().role() != UserContext.Role.ADMIN);
        p.addValue("owner", scope.ownerUserId());
        if (scope.ownerUserId() != null) {
            if (scope.actor().role() != UserContext.Role.ADMIN && scope.ownerUserId() != scope.actor().userId())
                throw LabException.denied();
        }
    }

    @FunctionalInterface
    public interface Projection<T> { T map(SqlRow row, int index); }

    public <T> List<T> project(List<SqlRow> rows, Projection<T> projection) {
        var result = new ArrayList<T>(rows.size());
        for (int i = 0; i < rows.size(); i++) result.add(projection.map(rows.get(i), i));
        return result;
    }

    public <T> T one(List<T> rows) {
        if (rows.size() != 1) throw new org.springframework.dao.IncorrectResultSizeDataAccessException(1, rows.size());
        return rows.get(0);
    }

    public <T> T scalar(List<SqlRow> rows, Class<T> type) { return scalarValue(one(rows).value(1), type); }
    public <T> List<T> scalars(List<SqlRow> rows, Class<T> type) {
        return project(rows, (row, index) -> scalarValue(row.value(1), type));
    }
    private <T> T scalarValue(Object value, Class<T> type) {
        if (value == null) return null;
        if (type == Long.class && value instanceof Number number) return type.cast(number.longValue());
        if (type == Integer.class && value instanceof Number number) return type.cast(number.intValue());
        if (type == String.class) return type.cast(value.toString());
        if (type == Timestamp.class && value instanceof LocalDateTime time) return type.cast(Timestamp.valueOf(time));
        return type.cast(value);
    }

    public long insert(java.util.function.ToIntFunction<com.example.ailab.data.persistence.po.InsertCommand> statement, Object... args) {
        var command = new com.example.ailab.data.persistence.po.InsertCommand(args);
        if (statement.applyAsInt(command) != 1 || command.getId() == null)
            throw new IllegalStateException("数据库未返回新增记录的主键");
        return command.getId();
    }


    /**
     * 规范化哈希输入，写操作不接受客户端提供的哈希作为事实。
     */
    public static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 原文元数据转换，不泄露路径或内部账号凭证。
     */
    public static DocumentSnapshot document(SqlRow r, int n) {
        return new DocumentSnapshot(r.longValue("id"), r.longValue("knowledge_base_id"), r.longValue("owner_user_id"), r.string("title"), r.string("format"), r.intValue("current_version"), r.string("ingestion_status"), (Long) r.value("active_processing_revision"));
    }

    /**
     * Outbox 与权威业务记录处于同一事务。
     */
    public void event(String type, long id, long version) {
        mapper.eventOutboxEventsWrite(new Object[]{type, id, version});
    }

    /** 全局知识纪元覆盖库／内容更新与处理激活；缓存不自行相信TTL。 */
    public long knowledgeEpoch() {
        return scalar(mapper.knowledgeEpochSystemControlSelect(new Object[]{}), Long.class);
    }

    /** SELF／SELECTED至多一百库的版本摘要；ALL使用全局纪元，避免枚举无限范围。 */
    public String cacheScopeState(AuthorizedKnowledgeScope scope) {
        var parameters = new SqlParameters();
        scope(scope, parameters);
        if (scope.mode() == ScopeRequest.Mode.ALL) return "ALL_EPOCH";
        var versions = project(mapper.cacheScopeStateKnowledgeBasesSelect(parameters), (r, n) -> r.longValue(1) + ":" + r.longValue(2));
        if (versions.size() > 100) throw LabException.invalid("缓存知识范围超限");
        return hash(versions.toString());
    }

    /** 可靠审计同步写入，失败拒绝跨库结果；不复用可丢观测队列或保存查询文本。 */
    public void audit(AuthorizedKnowledgeScope scope, String action, Long id, int count) {
        audit(scope, action, id, count, id == null ? List.of() : List.of(id));
    }

    /** 对象ID和完整筛选是可审计元数据，限一百项；不保存查询文本、向量、标题或正文。 */
    public void audit(AuthorizedKnowledgeScope scope, String action, Long id, int count, List<Long> resourceIds) {
        if (scope.actor().role() != UserContext.Role.ADMIN) return;
        actor(scope.actor(), false);
        if (resourceIds.size() > 100 || resourceIds.stream().anyMatch(value -> value == null || value <= 0))
            throw LabException.invalid("审计对象集合超限");
        // 列表元素仅为服务器验证过的正整数，固定JSON字段不接受任意用户表达式。
        String selection = "{\"knowledgeBaseIds\":" + scope.knowledgeBaseIds()
                + ",\"ownerUserId\":" + scope.ownerUserId() + "}";
        try {
            mapper.auditKnowledgeAccessAuditWrite(new Object[]{scope.actor().userId(), action, id, scope.mode().name(), scope.actor().permissionVersion(), knowledgeEpoch(), count, selection, resourceIds.stream().distinct().sorted().toList().toString()});
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new LabException("ACCESS_AUDIT_UNAVAILABLE", "访问审计暂不可用，结果未交付");
        }
    }

    /**
     * 资料更新使 ALL 缓存 epoch 单调增加，检索候选缓存按新纪元失效，正文仍实时复核。
     */
    public void changed() {
        mapper.changedSystemControlWrite(new Object[]{});
    }
}
