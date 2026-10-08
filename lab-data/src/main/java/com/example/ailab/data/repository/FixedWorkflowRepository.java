package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.mapper.FixedWorkflowMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.Instant;

/** 任务租约锁下保存不可变基线和节点；原文核验与发布使用同一数据库事务。 */
@Repository
@Transactional
public class FixedWorkflowRepository implements FixedWorkflowStorePort {
    private final TaskRepository tasks;
    private final DocumentSqlRepository documents;
    private final WorkflowRunStorePort workflows;
    private final FixedWorkflowMapper mapper;
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public FixedWorkflowRepository(TaskRepository tasks, DocumentSqlRepository documents, WorkflowRunStorePort workflows, SqlSupport sql) {
        this.tasks = tasks; this.documents = documents; this.workflows = workflows; this.mapper = sql.mapper(FixedWorkflowMapper.class);
    }
    public Optional<Learning.Baseline> baseline(TaskLease lease) {
        valid(lease);
        return baseline(lease.task().taskId());
    }
    private Optional<Learning.Baseline> baseline(long taskId) {
        var rows = mapper.baseline(new Object[]{taskId});
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        try {
            var value = json.readValue(row.string("baseline_json"), Learning.Baseline.class);
            if (!SqlSupport.hash(encode(value)).equals(row.string("baseline_hash"))) throw conflict();
            return Optional.of(value);
        } catch (Exception invalid) { throw conflict(); }
    }
    public void bind(TaskLease lease, Learning.Baseline value) {
        valid(lease);
        var old = baseline(lease);
        if (old.isPresent()) {
            if (!old.get().equals(value)) throw conflict();
            return;
        }
        if (value.workflow().architecture() != ExecutionArchitecture.FIXED
                || !workflows.binding(lease).filter(value.workflow()::equals).isPresent()
                || !value.limits().equals(Learning.Limits.forType(lease.request().taskType()))
                || value.slices().isEmpty() || value.slices().size() > 12) throw conflict();
        verify(documents, lease.actor(), lease.request().scope(), value);
        if (!value.slices().stream().map(Learning.SourceSlice::documentId).collect(java.util.stream.Collectors.toSet())
                .equals(new HashSet<>(lease.request().documentIds()))) throw conflict();
        mapper.bind(new Object[]{lease.task().taskId(), SqlSupport.hash(encode(value)), encode(value)});
    }
    public Optional<String> completed(TaskLease lease, String nodeId, String inputHash) {
        valid(lease); nodeId(nodeId); hash(inputHash);
        var rows = mapper.node(new Object[]{lease.task().taskId(), nodeId});
        if (rows.isEmpty()) return Optional.empty();
        var row = rows.get(0);
        if (!inputHash.equals(row.string("input_hash"))) throw conflict();
        return row.string("status").equals("COMPLETED") ? Optional.of(output(row)) : Optional.empty();
    }
    public void begin(TaskLease lease, String nodeId, String stage, String inputHash) {
        valid(lease); nodeId(nodeId); hash(inputHash);
        if (!TaskProgress.stepIds(lease.request().taskType()).contains(stage) || baseline(lease).isEmpty()) throw conflict();
        var rows = mapper.node(new Object[]{lease.task().taskId(), nodeId});
        if (!rows.isEmpty()) {
            if (!inputHash.equals(rows.get(0).string("input_hash")) || !stage.equals(rows.get(0).string("stage"))) throw conflict();
            return;
        }
        mapper.begin(new Object[]{lease.task().taskId(), nodeId, stage, inputHash});
        tasks.beginStep(lease, stage);
    }
    public void complete(TaskLease lease, String nodeId, String inputHash, String output) {
        valid(lease); nodeId(nodeId); hash(inputHash);
        String canonical = canonical(output);
        var rows = mapper.node(new Object[]{lease.task().taskId(), nodeId});
        if (rows.isEmpty() || !inputHash.equals(rows.get(0).string("input_hash"))) throw conflict();
        if (rows.get(0).string("status").equals("COMPLETED")) {
            if (!canonical.equals(output(rows.get(0)))) throw conflict();
            return;
        }
        if (mapper.complete(new Object[]{SqlSupport.hash(canonical), canonical, lease.task().taskId(), nodeId, inputHash}) != 1) throw conflict();
    }
    public void finishStage(TaskLease lease, String stage) {
        valid(lease);
        if (!TaskProgress.stepIds(lease.request().taskType()).contains(stage) || stage.equals("publish")) throw conflict();
        if (mapper.stage(new Object[]{lease.task().taskId(), stage}) > 0) tasks.progressChanged(lease);
    }
    public void repair(TaskLease lease, String reviewHash) {
        valid(lease); hash(reviewHash);
        var rows = mapper.baseline(new Object[]{lease.task().taskId()});
        if (rows.isEmpty()) throw conflict();
        String old = rows.get(0).string("repair_hash");
        if (old != null) {
            if (!old.equals(reviewHash)) throw new LabException("BUDGET_EXCEEDED", "唯一语义修复额度耗尽");
            return;
        }
        if (mapper.repair(new Object[]{reviewHash, lease.task().taskId()}) != 1) throw conflict();
    }
    public void publish(TaskLease lease, String resultJson, String markdown) {
        valid(lease);
        var value = baseline(lease).orElseThrow(FixedWorkflowRepository::conflict);
        verify(documents, lease.actor(), lease.request().scope(), value);
        for (var slice : value.slices()) {
            var nodes = mapper.node(new Object[]{lease.task().taskId(), "read_" + slice.id()});
            if (nodes.isEmpty() || !nodes.get(0).string("status").equals("COMPLETED")) throw conflict();
            output(nodes.get(0));
        }
        var result = mapper.node(new Object[]{lease.task().taskId(), "result"});
        if (result.isEmpty() || !canonical(resultJson).equals(output(result.get(0)))) throw conflict();
        tasks.publish(lease, new TaskCheckpoint("result", markdown, value.sources(), false));
    }
    public String result(UserContext actor, long taskId) {
        var task = tasks.read(actor, taskId);
        if (!Learning.supports(task.taskType()) || !task.status().equals("SUCCEEDED"))
            throw new LabException("WORKFLOW_RESULT_UNAVAILABLE", "学习结果尚未发布或任务类型不支持");
        var value = baseline(taskId).orElseThrow(FixedWorkflowRepository::conflict);
        verify(documents, actor, null, value);
        var rows = mapper.node(new Object[]{taskId, "result"});
        if (rows.isEmpty()) throw conflict();
        return output(rows.get(0));
    }
    /** Markdown下载与结构化结果采用同一原文版本及处理代次规则。 */
    static void verifyArtifactBaseline(SqlRow row, UserContext actor, DocumentSqlRepository documents) {
        if (row.get("fixed_baseline") == null) return;
        try {
            var json = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
            var value = json.readValue(row.string("fixed_baseline"), Learning.Baseline.class);
            if (!SqlSupport.hash(json.writeValueAsString(value)).equals(row.string("fixed_baseline_hash"))) throw conflict();
            verify(documents, actor, null, value);
        } catch (LabException failure) { throw failure; }
        catch (Exception invalid) { throw conflict(); }
    }
    private static void verify(DocumentSqlRepository documents, UserContext actor, ScopeRequest request, Learning.Baseline value) {
        var scope = request == null
                ? new AuthorizedKnowledgeScope(actor, actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF, List.of(), null, Instant.now())
                : new AuthorizedKnowledgeScope(actor, request.mode(), request.knowledgeBaseIds(), request.ownerUserId(), Instant.now());
        documents.verifySources(scope, value.sources());
        var cursors = new HashMap<Long, Integer>();
        for (var slice : value.slices()) {
            var document = documents.read(scope, slice.documentId());
            var snapshot = document.document();
            if (snapshot.knowledgeBaseId() != slice.knowledgeBaseId() || snapshot.documentVersion() != slice.documentVersion()
                    || !Objects.equals(snapshot.activeProcessingRevision(), slice.processingRevision())
                    || slice.startOffset() != cursors.getOrDefault(slice.documentId(), 0)
                    || !TextWindow.boundary(document.text(), slice.startOffset()) || !TextWindow.boundary(document.text(), slice.endOffset())
                    || slice.endOffset() <= slice.startOffset()
                    || !Learning.digest(document.text().substring(slice.startOffset(), slice.endOffset())).equals(slice.textHash())) throw conflict();
            cursors.put(slice.documentId(), slice.endOffset());
        }
        for (var entry : cursors.entrySet())
            if (entry.getValue() != documents.read(scope, entry.getKey()).text().length()) throw conflict();
    }
    private void valid(TaskLease lease) {
        tasks.valid(lease);
        if (!Learning.supports(lease.request().taskType())) throw conflict();
    }
    private String output(SqlRow row) {
        if (!row.string("status").equals("COMPLETED")) throw conflict();
        String output = canonical(row.string("output_json"));
        if (!SqlSupport.hash(output).equals(row.string("output_hash"))) throw conflict();
        return output;
    }
    private String canonical(String text) {
        try {
            if (text == null || TextWindow.count(text) > 500000) throw conflict();
            // JSON字段顺序可由MySQL改变；递归有序Map形成唯一摘要。
            return json.writeValueAsString(json.readValue(text, Object.class));
        } catch (Exception invalid) { throw conflict(); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw conflict(); }
    }
    private void nodeId(String id) { if (id == null || !id.matches("[a-z][a-z0-9_]{0,63}")) throw conflict(); }
    private void hash(String hash) { if (hash == null || !hash.matches("[a-f0-9]{64}")) throw conflict(); }
    private static LabException conflict() { return new LabException("WORKFLOW_STATE_CONFLICT", "固定工作流基线、来源或检查点不一致"); }
}
