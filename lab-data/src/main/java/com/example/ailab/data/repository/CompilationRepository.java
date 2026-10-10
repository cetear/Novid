package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.*;
import com.example.ailab.data.persistence.mapper.CompilationMapper;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Repository
@Transactional
public class CompilationRepository implements CompilationStorePort {
    private final SqlSupport sql;
    private final CompilationMapper mapper;
    private final TaskRepository tasks;
    private final ContentWorkflowStorePort content;
    private final ApprovalRepository approvals;
    private final DocumentSqlRepository documents;
    private final ObjectMapper json = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public CompilationRepository(SqlSupport sql, TaskRepository tasks, ContentWorkflowStorePort content,
            ApprovalRepository approvals, DocumentSqlRepository documents) {
        this.sql = sql; this.mapper = sql.mapper(CompilationMapper.class); this.tasks = tasks;
        this.content = content; this.approvals = approvals; this.documents = documents;
    }
    public Compilation.SaveStatus prepare(UserContext actor, long taskId, long base, String key) {
        sql.owner(actor, base, true);
        var previous = mapper.preparation(new Object[]{actor.userId(), key});
        if (!previous.isEmpty()) {
            var row = previous.get(0);
            if (row.longValue("task_id") != taskId || row.longValue("target_base_id") != base)
                throw new LabException("OPERATION_CONFLICT", "相同保存键绑定了不同任务或目标库");
            return status(actor, row.string("approval_id"));
        }
        var task = tasks.read(actor, taskId);
        if (!"KNOWLEDGE_COMPILATION".equals(task.taskType()) || !"SUCCEEDED".equals(task.status()))
            throw new LabException("WORKFLOW_RESULT_UNAVAILABLE", "仅本人成功的资料整编任务可以保存");
        String result = content.result(actor, taskId);
        Learning.Result learning;
        try {
            var tree = json.readTree(result); ((com.fasterxml.jackson.databind.node.ObjectNode) tree).remove(java.util.List.of("contentPlan","coverageReport"));
            learning = json.treeToValue(tree, Learning.Result.class);
        } catch (Exception invalid) { throw new LabException("WORKFLOW_STATE_CONFLICT", "整编结果结构损坏"); }
        var rendered = CompilationDocumentRenderer.render(learning);
        var permissionSources = content.publishedSources(actor, taskId);
        if (permissionSources.isEmpty() || !permissionSources.containsAll(rendered.sources()))
            throw new LabException("WORKFLOW_STATE_CONFLICT", "整编证据与已发布权限依赖不一致");
        var approval = approvals.prepare(actor, base, rendered.title(), rendered.text(), permissionSources);
        mapper.prepare(new Object[]{approval.approvalId(), actor.userId(), key, taskId, base, SqlSupport.hash(result),
                encode(rendered.sources()), encode(rendered.mappings())});
        return new Compilation.SaveStatus(approval, null);
    }
    public Compilation.SaveStatus status(UserContext actor, String id) {
        sql.actor(actor, true);
        if (mapper.approval(new Object[]{id, actor.userId()}).isEmpty()) throw LabException.denied();
        var approval = approvals.read(actor, id);
        DocumentSnapshot document = null;
        if (approval.documentId() != null) document = documents.read(all(actor), approval.documentId()).document();
        return new Compilation.SaveStatus(approval, document);
    }
    public Compilation.Origin origin(AuthorizedKnowledgeScope scope, long id) {
        var document = documents.read(scope, id);
        var rows = mapper.origin(new Object[]{id, document.document().documentVersion()});
        if (rows.isEmpty()) {
            boolean generated = mapper.generated(new Object[]{id}).get(0).booleanValue("generated");
            return new Compilation.Origin(generated ? "GENERATED_NOTE" : "UPLOAD", 0, null, null, null, false, List.of(), List.of());
        }
        var row = rows.get(0); int version = row.intValue("document_version");
        var sources = new ArrayList<Compilation.Source>();
        for (var edge : mapper.sources(new Object[]{id, version})) {
            try {
                documents.verifySources(all(scope.actor()), List.of(new SourceDependency(edge.longValue("source_base_id"),
                        edge.longValue("source_document_id"), edge.intValue("source_document_version"))));
                var source = documents.metadata(edge.longValue("source_document_id"));
                sources.add(new Compilation.Source(edge.longValue("source_base_id"), source.id(), edge.intValue("source_document_version"),
                        edge.intValue("source_order"), source.title()));
            } catch (LabException failure) { if (!"ACCESS_DENIED".equals(failure.code())) throw failure; }
        }
        boolean valid = version == document.document().documentVersion() && row.string("content_hash").equals(SqlSupport.hash(document.text()));
        List<Compilation.SectionMapping> mappings;
        try { mappings = valid ? json.readValue(row.string("mapping_json"), json.getTypeFactory().constructCollectionType(List.class, Compilation.SectionMapping.class)) : List.of(); }
        catch (Exception failure) { throw new LabException("CONTEXT_MAPPING_INVALID", "整编章节映射损坏"); }
        sql.audit(scope, "READ_COMPILATION_ORIGIN", id, 1);
        var revision=document.document().activeProcessingRevision();
        var bindings=valid&&revision!=null?mapper.bindings(new Object[]{id,version,revision}).stream().map(CompilationRepository::parsedBinding).toList():List.<Compilation.ParsedBinding>of();
        String bindingStatus=!valid?"INVALIDATED":revision==null?"PENDING_PARSE":bindings.isEmpty()?"UNBOUND":"BOUND";
        return new Compilation.Origin("KNOWLEDGE_COMPILATION", version, row.longValue("task_id"), row.string("result_hash"),
                row.timestamp("generated_at").toInstant(), valid, sources, mappings,bindingStatus,bindings);
    }
    static Compilation.ParsedBinding parsedBinding(com.example.ailab.data.persistence.po.SqlRow r) {
        return new Compilation.ParsedBinding(r.string("chapter_id"),r.string("logical_section_id"),r.string("parsed_section_id"),r.longValue("processing_revision"),
                r.intValue("start_offset"),r.intValue("end_offset"),r.string("mapping_hash"));
    }
    public List<Compilation.Participation> participations(AuthorizedKnowledgeScope scope, long id, int offset, int limit) {
        documents.read(scope, id);
        var result = new ArrayList<Compilation.Participation>();
        for (var row : mapper.participations(new Object[]{id, offset, limit})) {
            long compiledId = row.longValue("compiled_document_id");
            try {
                var document = documents.read(all(scope.actor()), compiledId).document();
                var versions = mapper.sourceVersions(new Object[]{id, compiledId}).stream().map(v -> v.intValue("source_document_version")).toList();
                result.add(new Compilation.Participation(compiledId, document.knowledgeBaseId(), document.title(), document.documentVersion(), versions));
            } catch (LabException failure) { if (!"ACCESS_DENIED".equals(failure.code())) throw failure; }
        }
        sql.audit(scope, "LIST_COMPILATIONS", id, result.size(), result.stream().map(Compilation.Participation::documentId).toList());
        return List.copyOf(result);
    }
    private AuthorizedKnowledgeScope all(UserContext actor) {
        return new AuthorizedKnowledgeScope(actor, actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF,
                List.of(), null, java.time.Instant.now());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception failure) { throw new IllegalStateException("整编来源序列化失败", failure); }
    }
}
