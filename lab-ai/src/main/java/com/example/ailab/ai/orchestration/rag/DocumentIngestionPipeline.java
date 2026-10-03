package com.example.ailab.ai.orchestration.rag;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.model.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;

/**
 * 入库远程步骤在 MySQL 写事务外执行，只有完整搜索验证后才激活。
 */
@Component
public class DocumentIngestionPipeline {
    private final DocumentIngestionStorePort store;
    private final KnowledgeCapabilityPort knowledge;
    private final StructureParser parser;
    private final ModelGateway models;
    private final KnowledgeIndexPort index;

    /**
     * 通过窄端口装配，编排不访问 Mapper 或 ES Client。
     */
    public DocumentIngestionPipeline(DocumentIngestionStorePort s, KnowledgeCapabilityPort k, StructureParser p, ModelGateway m, KnowledgeIndexPort i) {
        store = s;
        knowledge = k;
        parser = p;
        models = m;
        index = i;
    }

    /**
     * 每个新远程步骤前复核租约、用户及来源。
     */
    public void execute(IngestionLease lease) {
        var budget = new ExecutionBudget(Duration.ofMinutes(10), 160);
        var scope = ScopeRequest.self();
        var content = knowledge.document(lease.actor(), scope, lease.documentId());
        if (content.document().documentVersion() != lease.documentVersion())
            throw new LabException("STALE_EXECUTION", "内容版本已变化");
        var start = Instant.now();
        var parsed = parser.parse(lease);
        if (Duration.between(start, Instant.now()).toSeconds() > 30)
            throw new LabException("DOCUMENT_PARSE_FAILED", "解析超过 30 秒");
        store.saveStructure(lease, parsed);
        var indexed = new ArrayList<IndexedChunk>();
        // 所有小片合计输入先预估，超限在付费调用前拒绝。
        long total = parsed.chunks().stream().mapToLong(c -> c.embeddingText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length).sum();
        if (total > 2500000) throw new LabException("INGESTION_BUDGET_EXCEEDED", "入库总输入超过有限预算");
        for (int offset = 0; offset < parsed.chunks().size(); offset += 32) {
            if (!store.renew(lease)) throw new LabException("STALE_EXECUTION", "租约失效");
            knowledge.document(lease.actor(), scope, lease.documentId());
            var batch = parsed.chunks().subList(offset, Math.min(offset + 32, parsed.chunks().size()));
            var vectors = models.embed(batch.stream().map(ChunkSnapshot::embeddingText).toList(), budget);
            for (int i = 0; i < batch.size(); i++)
                indexed.add(new IndexedChunk(content.document().knowledgeBaseId(), content.document().ownerUserId(), lease.documentId(), lease.documentVersion(), lease.processingRevision(), batch.get(i), vectors.vectors().get(i), vectors.modelVersion()));
        }
        if (!store.renew(lease)) throw new LabException("STALE_EXECUTION", "租约失效");
        knowledge.document(lease.actor(), scope, lease.documentId());
        index.index(indexed);
        index.verify(indexed);
        budget.check();
        store.activate(lease, indexed.size());
    }
}
