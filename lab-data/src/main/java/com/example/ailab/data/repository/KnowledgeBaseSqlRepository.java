package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.KnowledgeBaseRepository;
import com.example.ailab.contract.error.LabException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 知识库 owner、版本与启用状态的 MySQL 实现。
 */
@Repository
public class KnowledgeBaseSqlRepository implements KnowledgeBaseRepository {
    private final SqlSupport sql;

    /**
     * 注入内部 SQL 组件。
     */
    public KnowledgeBaseSqlRepository(SqlSupport sql) {
        this.sql = sql;
    }

    /**
     * 元数据供业务统一策略判断，不能直接暴露给 HTTP。
     */
    public Optional<KnowledgeBaseSnapshot> find(long id) {
        return sql.jdbc.query("SELECT * FROM knowledge_bases WHERE id=?", SqlSupport::base, id).stream().findFirst();
    }

    /**
     * 创建归属固定且限额为每用户 100 个未删除库。
     */
    @Transactional
    public KnowledgeBaseSnapshot create(UserContext actor, String name, String description) {
        sql.actor(actor, true);
        if (sql.jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_bases WHERE owner_user_id=? AND deleted=FALSE", Long.class, actor.userId()) >= 100)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "每用户最多 100 个库");
        long id = sql.insert("INSERT INTO knowledge_bases(owner_user_id,name,description) VALUES(?,?,?)", actor.userId(), name, description);
        sql.changed();
        return find(id).orElseThrow();
    }

    /**
     * ALL 使用过滤条件，不枚举全量 ID。
     */
    public List<KnowledgeBaseSnapshot> list(AuthorizedKnowledgeScope scope, int offset, int limit) {
        var p = new MapSqlParameterSource().addValue("offset", offset).addValue("limit", limit);
        String filter = sql.scope(scope, p);
        audit(scope.actor(), "LIST_BASES", null);
        return sql.named.query("SELECT k.* FROM knowledge_bases k WHERE " + filter + " ORDER BY k.id LIMIT :limit OFFSET :offset", p, SqlSupport::base);
    }

    /**
     * owner 与期望版本同时满足才能修改。
     */
    @Transactional
    public KnowledgeBaseSnapshot update(UserContext actor, long id, long version, String name, String description, boolean enabled) {
        sql.owner(actor, id, false);
        if (sql.jdbc.update("UPDATE knowledge_bases SET name=?,description=?,enabled=?,version=version+1 WHERE id=? AND version=?", name, description, enabled, id, version) != 1)
            throw new LabException("OPERATION_CONFLICT", "知识库版本已变化");
        sql.changed();
        return find(id).orElseThrow();
    }

    /**
     * 先使 MySQL 不可读，再由 Outbox 处理 ES 清理。
     */
    @Transactional
    public void delete(UserContext actor, long id, long version) {
        sql.owner(actor, id, false);
        if (sql.jdbc.update("UPDATE knowledge_bases SET deleted=TRUE,version=version+1 WHERE id=? AND version=?", id, version) != 1)
            throw new LabException("OPERATION_CONFLICT", "知识库版本已变化");
        sql.event("DELETE_BASE", id, version + 1);
        sql.changed();
    }

    /**
     * 管理员读取范围记录元数据，不记录原文。
     */
    private void audit(UserContext actor, String action, Long id) {
        if (actor.role() == UserContext.Role.ADMIN)
            sql.jdbc.update("INSERT INTO knowledge_access_audit(actor_user_id,action,resource_id) VALUES(?,?,?)", actor.userId(), action, id);
    }
}
