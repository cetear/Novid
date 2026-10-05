package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.ApprovalMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.OperationStorePort;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * 保存笔记的唯一原子提交入口，不在事务中调用模型或 ES。
 */
@Repository
public class ApprovalRepository implements OperationStorePort {
    private final ApprovalMapper mapper;
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 准备与确认均复用真实原文和服务器来源。
     */
    public ApprovalRepository(SqlSupport sql, DocumentSqlRepository docs) {
        this.sql = sql; this.mapper = sql.mapper(ApprovalMapper.class);
        this.docs = docs;
    }

    /**
     * 来源扁平化、去重并绑定目标版本与完整参数。
     */
    @Transactional
    public ApprovalSnapshot prepare(UserContext actor, long base, String title, String content, List<SourceDependency> sources) {
        var target = sql.owner(actor, base, true);
        var flattened = flatten(sources, new LinkedHashSet<>(), new HashSet<>(), 0);
        docs.verifySources(all(actor), flattened);
        String id = UUID.randomUUID().toString(), op = UUID.randomUUID().toString(), sourceJson = encode(flattened);
        String hash = SqlSupport.hash(base + "\n" + target.version() + "\n" + title.length() + ":" + title + "\n" + content + "\n" + sourceJson);
        mapper.prepareApprovalsWrite(new Object[]{id, op, actor.userId(), base, target.version(), title, content, hash, sourceJson});
        return load(actor, id, false);
    }

    /**
     * 本人预览与所有来源重新核验，ADMIN 不读取他人确认。
     */
    public ApprovalSnapshot read(UserContext actor, String id) {
        sql.actor(actor, false);
        var approval = load(actor, id, false);
        docs.verifySources(all(actor), approval.sourceDependencies());
        return approval;
    }

    /**
     * 确认只接收决定；参数、operationId、目标全部来自服务器。
     */
    @Transactional
    public ApprovalSnapshot decide(UserContext actor, String id, boolean approve) {
        sql.actor(actor, true);
        var a = load(actor, id, true);
        // 重放成功只返回既有事实，仍复核当前来源，绝不创建第二份文档。
        docs.verifySources(all(actor), a.sourceDependencies());
        if (a.status().equals("APPROVED")) {
            if (!approve) throw new LabException("APPROVAL_CONFLICT", "确认已消费");
            return a;
        }
        if (!a.status().equals("WAITING")) throw new LabException("APPROVAL_CONFLICT", "确认已被处理");
        if (!a.expiresAt().isAfter(Instant.now())) throw new LabException("APPROVAL_EXPIRED", "确认已过期");
        if (!approve) {
            mapper.decideApprovalsWrite(new Object[]{id});
            return load(actor, id, false);
        }
        var target = sql.owner(actor, a.knowledgeBaseId(), true);
        if (target.version() != a.targetVersion())
            throw new LabException("APPROVAL_CONFLICT", "目标知识库已变化，请重新确认");
        String hash = sql.scalar(mapper.decideApprovalsSelect(new Object[]{id}), String.class);
        long doc = docs.insert(actor, a.knowledgeBaseId(), a.title(), "md", a.content(), true);
        for (var source : a.sourceDependencies())
            mapper.decideSourceDependenciesWrite(new Object[]{doc, source.knowledgeBaseId(), source.documentId(), source.documentVersion()});
        mapper.decideOperationsWrite(new Object[]{a.operationId(), actor.userId(), hash, doc});
        mapper.decideApprovalsWrite2(new Object[]{doc, id});
        return load(actor, id, false);
    }

    /**
     * 确认归属过滤始终位于 SQL 内。
     */
    private ApprovalSnapshot load(UserContext actor, String id, boolean lock) {
        return sql.project(mapper.loadApprovalsSelect(new Object[]{id, actor.userId()}, lock), this::map).stream().findFirst().orElseThrow(LabException::denied);
    }

    /**
     * 数据行转换为完整但无内部凭证的预览。
     */
    private ApprovalSnapshot map(SqlRow r, int n) {
        return new ApprovalSnapshot(r.string("approval_id"), r.string("operation_id"), r.longValue("actor_user_id"), r.longValue("knowledge_base_id"), r.longValue("target_version"), r.string("title"), r.string("content"), decode(r.string("source_json")), r.timestamp("expires_at").toInstant(), r.string("status"), (Long) r.value("document_id"));
    }

    /**
     * 衍生来源不能被多次生成隐藏，限制总数／深度且拒绝环。
     */
    private List<SourceDependency> flatten(List<SourceDependency> inputs, Set<SourceDependency> out, Set<String> path, int depth) {
        if (depth > 8) throw new LabException("CONTEXT_MAPPING_INVALID", "来源层级超过限制");
        for (var s : inputs) {
            String key = s.documentId() + ":" + s.documentVersion();
            if (!path.add(key)) throw new LabException("CONTEXT_MAPPING_INVALID", "来源环");
            out.add(s);
            if (out.size() > 32) throw new LabException("CONTEXT_MAPPING_INVALID", "来源总数超过限制");
            flatten(docs.dependencies(s.documentId(), s.documentVersion()), out, path, depth + 1);
            path.remove(key);
        }
        return out.stream().sorted(Comparator.comparingLong(SourceDependency::documentId).thenComparingInt(SourceDependency::documentVersion)).toList();
    }

    /**
     * 来源复核按当前角色可读范围，不受 UI 当前库选择影响。
     */
    private AuthorizedKnowledgeScope all(UserContext actor) {
        return new AuthorizedKnowledgeScope(actor, actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF, List.of(), null, Instant.now());
    }

    /**
     * 稳定顺序序列化仅存数据，不保存可执行对象。
     */
    private String encode(List<SourceDependency> sources) {
        try {
            return json.writeValueAsString(sources);
        } catch (Exception e) {
            throw new IllegalStateException("来源序列化失败", e);
        }
    }

    /**
     * 有类型 JSON 反序列化，不启用任意类型解析。
     */
    private List<SourceDependency> decode(String value) {
        try {
            return json.readValue(value, new TypeReference<List<SourceDependency>>() {
            });
        } catch (Exception e) {
            throw new LabException("CONTEXT_MAPPING_INVALID", "来源记录损坏");
        }
    }
}
