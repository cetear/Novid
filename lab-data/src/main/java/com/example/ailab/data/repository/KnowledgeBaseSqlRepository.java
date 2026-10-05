package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.KnowledgeBaseMapper;
import com.example.ailab.data.persistence.po.KnowledgeBasePo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.KnowledgeBaseRepository;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.po.SqlParameters;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 知识库 owner、版本与启用状态的 MySQL 实现。
 */
@Repository
public class KnowledgeBaseSqlRepository implements KnowledgeBaseRepository {
    private final KnowledgeBaseMapper mapper;
    private final SqlSupport sql;

    /**
     * 注入内部 SQL 组件。
     */
    public KnowledgeBaseSqlRepository(SqlSupport sql) {
        this.sql = sql; this.mapper = sql.mapper(KnowledgeBaseMapper.class);
    }

    /**
     * 元数据供业务统一策略判断，不能直接暴露给 HTTP。
     */
    public Optional<KnowledgeBaseSnapshot> find(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(KnowledgeBasePo::snapshot);
    }

    /**
     * 创建归属固定且限额为每用户 100 个未删除库。
     */
    @Transactional
    public KnowledgeBaseSnapshot create(UserContext actor, String name, String description) {
        sql.actor(actor, true);
        if (mapper.selectCount(new QueryWrapper<KnowledgeBasePo>().eq("owner_user_id", actor.userId()).eq("deleted", false)) >= 100)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "每用户最多 100 个库");
        var base = new KnowledgeBasePo();
        base.ownerUserId = actor.userId(); base.name = name; base.description = description;
        mapper.insert(base);
        sql.changed();
        return find(base.id).orElseThrow();
    }

    /**
     * ALL 使用过滤条件，不枚举全量 ID。
     */
    @Transactional
    public List<KnowledgeBaseSnapshot> list(AuthorizedKnowledgeScope scope, int offset, int limit) {
        sql.actor(scope.actor(), true);
        var p = new SqlParameters().addValue("offset", offset).addValue("limit", limit);
        sql.scope(scope, p);
        var result = mapper.listAuthorized(p).stream().map(KnowledgeBasePo::snapshot).toList();
        sql.audit(scope, "LIST_BASES", null, result.size(), result.stream().map(KnowledgeBaseSnapshot::id).toList());
        return result;
    }

    /**
     * owner 与期望版本同时满足才能修改。
     */
    @Transactional
    public KnowledgeBaseSnapshot update(UserContext actor, long id, long version, String name, String description, boolean enabled) {
        sql.owner(actor, id, false);
        if (mapper.update(null, new UpdateWrapper<KnowledgeBasePo>().eq("id", id).eq("version", version)
                .set("name", name).set("description", description).set("enabled", enabled).setSql("version=version+1")) != 1)
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
        if (mapper.update(null, new UpdateWrapper<KnowledgeBasePo>().eq("id", id).eq("version", version)
                .set("deleted", true).setSql("version=version+1")) != 1)
            throw new LabException("OPERATION_CONFLICT", "知识库版本已变化");
        sql.event("DELETE_BASE", id, version + 1);
        sql.changed();
    }

    /** 已由业务判断的本人／管理员元数据读取，事务内再次核验并可靠审计。 */
    @Transactional
    public KnowledgeBaseSnapshot read(UserContext actor, long id) {
        sql.actor(actor, true);
        var base = find(id).orElseThrow(LabException::denied);
        if (base.deleted() || base.ownerUserId() != actor.userId()
                && (actor.role() != UserContext.Role.ADMIN || !base.enabled())) throw LabException.denied();
        sql.audit(new AuthorizedKnowledgeScope(actor, ScopeRequest.Mode.SELECTED, List.of(id), null, java.time.Instant.now()), "READ_BASE", id, 1);
        return base;
    }
}
