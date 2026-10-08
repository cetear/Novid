package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.tools.ToolSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;

/** 完整有界读取，初次基线保存前检查所有文档；原文位置和处理代次在每次模型调用前重核。 */
public final class LearningSourceReader {
    public static final int MAX_BYTES = 24000, PAGE_BYTES = 3000, MAX_PAGES = 12;
    private final KnowledgeCapabilityPort knowledge;
    private final TaskStorePort tasks;
    public LearningSourceReader(KnowledgeCapabilityPort knowledge, TaskStorePort tasks) { this.knowledge = knowledge; this.tasks = tasks; }
    public Learning.Baseline prepare(TaskLease lease, WorkflowExecutionBinding workflow, TaskExecutionBinding skill) {
        knowledge.authorize(lease.actor(), lease.request().scope());
        var slices = new ArrayList<Learning.SourceSlice>(); var sources = new LinkedHashSet<SourceDependency>();
        int total = 0;
        for (long id : lease.request().documentIds().stream().sorted().toList()) {
            var content = knowledge.document(lease.actor(), lease.request().scope(), id); var document = content.document();
            if (document.activeProcessingRevision() == null) throw new LabException("INDEX_NOT_READY", "学习资料需先完成结构入库");
            total += TextWindow.count(content.text());
            if (total > MAX_BYTES || content.text().isBlank()) throw new LabException("WORKFLOW_INPUT_TOO_LARGE", "资料为空或超过24000 UTF-8字节，请缩小本次范围");
            sources.add(new SourceDependency(document.knowledgeBaseId(), id, document.documentVersion())); sources.addAll(content.sourceDependencies());
            int start = 0;
            while (start < content.text().length()) {
                int end = TextWindow.end(content.text(), start, content.text().length(), PAGE_BYTES);
                if (end <= start || slices.size() >= MAX_PAGES) throw new LabException("WORKFLOW_INPUT_TOO_LARGE", "资料分批超过12批，请缩小本次范围");
                slices.add(new Learning.SourceSlice(String.format(Locale.ROOT, "p%02d", slices.size() + 1), document.knowledgeBaseId(), id,
                        document.documentVersion(), document.activeProcessingRevision(), document.title(), start, end,
                        Learning.digest(content.text().substring(start, end))));
                start = end;
            }
        }
        if (sources.size() > 32) throw new LabException("WORKFLOW_INPUT_TOO_LARGE", "继承来源超过32项");
        return new Learning.Baseline(workflow, skill, ToolSchema.hash(lease.request()), slices, List.copyOf(sources), Learning.Limits.forType(lease.request().taskType()));
    }
    public String text(TaskLease lease, Learning.SourceSlice slice) {
        var content = knowledge.document(lease.actor(), lease.request().scope(), slice.documentId()); var document = content.document();
        if (document.documentVersion() != slice.documentVersion() || document.knowledgeBaseId() != slice.knowledgeBaseId()
                || !Objects.equals(document.activeProcessingRevision(), slice.processingRevision())
                || !TextWindow.boundary(content.text(), slice.startOffset()) || !TextWindow.boundary(content.text(), slice.endOffset())
                || slice.startOffset() >= slice.endOffset()) throw changed();
        String text = content.text().substring(slice.startOffset(), slice.endOffset());
        if (!Learning.digest(text).equals(slice.textHash())) throw changed();
        return text;
    }
    public void verify(TaskLease lease, Learning.Baseline baseline) {
        if (!tasks.renew(lease)) throw new LabException("STALE_EXECUTION", "学习任务执行权失效");
        knowledge.authorize(lease.actor(), lease.request().scope());
        if (!baseline.requestHash().equals(ToolSchema.hash(lease.request()))) throw changed();
        for (var source : baseline.sources()) {
            var document = knowledge.document(lease.actor(), lease.request().scope(), source.documentId()).document();
            if (document.documentVersion() != source.documentVersion() || document.knowledgeBaseId() != source.knowledgeBaseId()) throw changed();
        }
        baseline.slices().forEach(slice -> text(lease, slice));
    }
    private static LabException changed() { return new LabException("CONTEXT_VERSION_CONFLICT", "学习资料、处理代次或请求已变化"); }
}
