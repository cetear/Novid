package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.DocumentSqlMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentStorePort;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.po.SqlParameters;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 原文为权威数据，当前查询不会从 ES 借用旧版本。
 */
@Repository
public class DocumentSqlRepository implements DocumentStorePort {
    private final DocumentSqlMapper mapper;
    private final SqlSupport sql;
    public static final String JOIN = " FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id JOIN document_versions v ON v.document_id=d.id AND v.document_version=d.current_version ";

    /**
     * 装配同一数据源。
     */
    public DocumentSqlRepository(SqlSupport sql) {
        this.sql = sql; this.mapper = sql.mapper(DocumentSqlMapper.class);
    }

    /**
     * actor 行锁序列化同用户去重；创建与去重结果同事务提交。
     */
    @Transactional
    public DocumentSnapshot create(UserContext actor, UploadCommand c) {
        sql.owner(actor, c.knowledgeBaseId(), true);
        String hash = SqlSupport.hash(c.knowledgeBaseId() + "\n" + c.title().length() + ":" + c.title() + "\n" + c.format() + "\n" + c.text());
        var old = sql.project(mapper.createRequestDeduplicationsSelect(new Object[]{actor.userId(), c.idempotencyKey()}), (r, n) -> Map.entry(r.string(1), r.longValue(2)));
        if (!old.isEmpty()) {
            if (!old.get(0).getKey().equals(hash)) throw new LabException("OPERATION_CONFLICT", "相同去重键的参数不同");
            return metadata(old.get(0).getValue());
        }
        if (sql.scalar(mapper.createDocumentsSelect(new Object[]{actor.userId()}), Long.class) >= 10000)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "文档数量超过用户配额");
        long id = insert(actor, c.knowledgeBaseId(), c.title(), c.format(), c.text(), false);
        mapper.createRequestDeduplicationsWrite(new Object[]{actor.userId(), c.idempotencyKey(), hash, id});
        return metadata(id);
    }

    /**
     * data 内部新文档事实，调用者必须已经取得 owner 行锁。
     */
    public long insert(UserContext actor, long base, String title, String format, String text, boolean generated) {
        long id = sql.insert(command -> mapper.insertDocumentsInsert(command), base, actor.userId(), title, format, generated);
        mapper.insertDocumentVersionsWrite(new Object[]{id, text, SqlSupport.hash(text)});
        mapper.insertDocumentIngestionsWrite(new Object[]{id, actor.userId()});
        sql.event("INGEST_DOCUMENT", id, 1);
        sql.changed();
        return id;
    }

    /**
     * 内部无范围读取只供已经授权的事务结果使用。
     */
    public DocumentSnapshot metadata(long id) {
        return sql.project(mapper.metadataRowsSelect(new Object[]{id}), SqlSupport::document).stream().findFirst().orElseThrow(LabException::denied);
    }

    /**
     * 分页候选额外复核衍生来源，不泄露受限标题。
     */
    @Transactional
    public List<DocumentSnapshot> list(AuthorizedKnowledgeScope scope, int offset, int limit) {
        sql.actor(scope.actor(), true);
        var p = new SqlParameters().addValue("limit", limit).addValue("offset", offset);
        sql.scope(scope, p);
        var rows = sql.project(mapper.listRowsSelect(p), SqlSupport::document);
        // 受限结果被过滤，因此页内可能少于 limit；不能为了补足无界循环查询。
        var result = rows.stream().filter(d -> allowedSources(scope, d.id(), d.documentVersion())).toList();
        sql.audit(scope, "LIST_DOCUMENTS", null, result.size(), result.stream().map(DocumentSnapshot::id).toList());
        return result;
    }

    /**
     * 查询源文与来源递归复核，失败不返回正文。
     */
    // 读取无业务状态写入，权限拒绝可被上层候选过滤；不把合法候选的整批事务标成rollback-only。
    @Transactional(noRollbackFor = LabException.class)
    public DocumentContent read(AuthorizedKnowledgeScope scope, long id) {
        sql.actor(scope.actor(), true);
        var p = new SqlParameters().addValue("id", id);
        sql.scope(scope, p);
        var docs = sql.project(mapper.readRowsSelect(p), SqlSupport::document);
        if (docs.isEmpty()) throw LabException.denied();
        var doc = docs.get(0);
        var sources = dependencies(id, doc.documentVersion());
        verifySources(scope, sources);
        String text = sql.scalar(mapper.readDocumentVersionsSelect(new Object[]{id, doc.documentVersion()}), String.class);
        sql.audit(scope, "READ_DOCUMENT", id, 1);
        return new DocumentContent(doc, text, sources);
    }

    /**
     * owner 与内容版本 CAS 修订，新版本保留此前服务端来源。
     */
    @Transactional
    public DocumentSnapshot revise(UserContext actor, long id, int version, String title, String text) {
        sql.actor(actor, true);
        var doc = metadata(id);
        sql.owner(actor, doc.knowledgeBaseId(), true);
        if (mapper.reviseDocumentsWrite(new Object[]{title, id, version}) != 1)
            throw new LabException("OPERATION_CONFLICT", "文档版本已变化");
        mapper.reviseDocumentVersionsWrite(new Object[]{id, version + 1, text, SqlSupport.hash(text)});
        mapper.reviseSourceDependenciesWrite(new Object[]{version + 1, id, version});
        mapper.reviseDocumentIngestionsWrite(new Object[]{id, version + 1, actor.userId()});
        sql.event("INGEST_DOCUMENT", id, version + 1);
        sql.changed();
        return metadata(id);
    }

    /**
     * 删除 MySQL 先生效，即使 ES 滞后也不得返回原文。
     */
    @Transactional
    public void delete(UserContext actor, long id, int version) {
        sql.actor(actor, true);
        var doc = metadata(id);
        sql.owner(actor, doc.knowledgeBaseId(), false);
        if (mapper.deleteDocumentsWrite(new Object[]{id, version}) != 1)
            throw new LabException("OPERATION_CONFLICT", "文档版本已变化");
        sql.event("DELETE_DOCUMENT", id, version);
        sql.changed();
    }

    /**
     * 统计使用与资料读取相同的范围，排除衍生来源当前不可读的文档。
     */
    @Transactional
    public KnowledgeStatistics statistics(AuthorizedKnowledgeScope scope) {
        sql.actor(scope.actor(), true);
        var p = new SqlParameters();
        sql.scope(scope, p);
        // 所有来源在保存时已扁平化，SQL 按当前用户角色／来源库状态复核。

        var result = sql.one(sql.project(mapper.statisticsRowsSelect(p), (r, n) -> new KnowledgeStatistics(r.longValue("total"), r.longValue("received"), r.longValue("ready"))));
        sql.audit(scope, "STATISTICS", null, 1);
        return result;
    }

    /**
     * 来源权限独立于当前选择范围，管理员降级后即使输出本人所有也拒绝。
     */
    public void verifySources(AuthorizedKnowledgeScope scope, List<SourceDependency> sources) {
        sql.actor(scope.actor(), false);
        walk(scope.actor(), sources, new HashSet<>(), new HashSet<>(), 0);
    }

    /**
     * 有限递归拒绝环、缺失历史版本与无权来源。
     */
    private void walk(UserContext actor, List<SourceDependency> sources, Set<String> path, Set<String> visited, int depth) {
        if (depth > 8 || sources.size() > 32) throw new LabException("CONTEXT_MAPPING_INVALID", "来源依赖超过限制");
        for (var s : sources) {
            String key = s.documentId() + ":" + s.documentVersion();
            if (path.contains(key)) throw new LabException("CONTEXT_MAPPING_INVALID", "来源依赖环");
            if (visited.contains(key)) continue;
            if (visited.size() >= 32) throw new LabException("CONTEXT_MAPPING_INVALID", "来源过多");
            var rows = sql.project(mapper.walkDocumentVersionsSelect(actor.role() == UserContext.Role.ADMIN ? new Object[]{s.documentId(), s.knowledgeBaseId(), s.documentVersion()} : new Object[]{s.documentId(), s.knowledgeBaseId(), s.documentVersion(), actor.userId()}, actor.role() != UserContext.Role.ADMIN), (r, n) -> r.longValue(1));
            if (rows.isEmpty()) throw LabException.denied();
            path.add(key);
            walk(actor, dependencies(s.documentId(), s.documentVersion()), path, visited, depth + 1);
            path.remove(key);
            visited.add(key);
        }
    }

    /**
     * 获取服务器保存的原始来源，不相信客户端清除标志。
     */
    public List<SourceDependency> dependencies(long id, int version) {
        return sql.project(mapper.dependenciesSourceDependenciesSelect(new Object[]{id, version}), (r, n) -> new SourceDependency(r.longValue(1), r.longValue(2), r.intValue(3)));
    }

    /**
     * 只过滤授权失败，基础设施故障必须正常上报。
     */
    private boolean allowedSources(AuthorizedKnowledgeScope scope, long id, int version) {
        try {
            verifySources(scope, dependencies(id, version));
            return true;
        } catch (LabException e) {
            if (e.code().equals("ACCESS_DENIED")) return false;
            throw e;
        }
    }
}
