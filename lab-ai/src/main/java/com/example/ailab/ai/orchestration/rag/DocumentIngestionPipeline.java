package com.example.ailab.ai.orchestration.rag;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.model.*;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * 逐批持久入库；模型和ES远程步骤始终位于数据库事务之外。
 */
@Component
public class DocumentIngestionPipeline {
    private TraceTelemetryPort telemetry = TraceTelemetryPort.NONE;

    /**
     * 追踪由正式入口装配，不进入持久批次预算和未知向量恢复判定。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void tracing(TraceTelemetryPort telemetry) {
        this.telemetry = telemetry;
    }

    private final DocumentIngestionStorePort store;
    private final KnowledgeCapabilityPort knowledge;
    private final StructureParser parser;
    private final ModelGateway models;
    private final KnowledgeIndexPort index;

    /**
     * 通过窄端口装配，不直接访问SQL、SDK或ES客户端。
     */
    public DocumentIngestionPipeline(DocumentIngestionStorePort s, KnowledgeCapabilityPort k, StructureParser p, ModelGateway m, KnowledgeIndexPort i) {
        store = s;
        knowledge = k;
        parser = p;
        models = m;
        index = i;
    }

    /**
     * 恢复同代次计划；全文结构有界，任意时刻仅持有一个批次的向量。
     */
    public void execute(IngestionLease lease) {
        String runId = UUID.randomUUID().toString();
        var observation = telemetry.open(runId, lease.actor().userId(), null, null, lease.ingestionId());
        try (var root = observation.span("INGESTION", "ingestion_execution")) {
            try {
                executeObserved(lease, root.context(), runId);
            } catch (RuntimeException failed) {
                root.fail(failed);
                throw failed;
            }
        } finally {
            observation.finish();
        }
    }

    /**
     * 每代次领取独立运行，已有向量恢复只记复用，绝不补造模型尝试。
     */
    private void executeObserved(IngestionLease lease, com.example.ailab.contract.context.TraceContext trace, String runId) {
        var content = trace.call("AUTHORIZATION", "verify", () -> current(lease));
        store.phase(lease, "PARSING");
        var start = Instant.now();
        var parsed = trace.call("PARSER", "parse", () -> parser.parse(lease));
        if (Duration.between(start, Instant.now()).toSeconds() > 30)
            throw new LabException("DOCUMENT_PARSE_FAILED", "解析超过30秒");
        store.phase(lease, "CHUNKING");
        store.saveStructure(lease, parsed);
        var plans = plans(lease, parsed);
        store.plan(lease, plans);
        for (var plan : plans) {
            try (var batchSpan = trace.span("BATCH", "batch", "batch_" + plan.ordinal(), null, List.of())) {
                var batchTrace = batchSpan.context();
                try {
                    current(lease);
                    var saved = store.batch(lease, plan.ordinal());
                    boolean reusedVectors = !saved.state().equals("PLANNED");
                    if (Set.of("SENDING", "UNKNOWN").contains(saved.state()))
                        throw new LabException("EMBEDDING_RESULT_UNKNOWN", "提供方无查询协议，不能自动重购未知向量");
                    var chunks = parsed.chunks().subList(plan.start(), plan.start() + plan.count());
                    if (saved.state().equals("PLANNED")) {
                        store.phase(lease, "EMBEDDING");
                        var persistent = store.budget(lease);
                        var budget = new ExecutionBudget(Duration.between(Instant.now(), persistent.deadline()), 160 - persistent.attempts(),
                                () -> store.beginEmbedding(lease, plan.ordinal())).traced(batchTrace)
                                .fees(new FeeScope(lease.actor(), "INGESTION", Long.toString(lease.ingestionId()), runId));
                        var result = models.embed(chunks.stream().map(ChunkSnapshot::embeddingText).toList(), budget);
                        store.completeEmbedding(lease, plan.ordinal(), result.vectors(), result.modelVersion(), result.inputTokens());
                        saved = new IngestionBatch(plan, "EMBEDDED", result.vectors(), result.modelVersion());
                    }
                    if (reusedVectors) {
                        try (var reused = batchTrace.span("CHECKPOINT", "persisted_vectors")) {
                            reused.status("REUSED");
                        }
                    }
                    var indexed = items(content.document(), lease, chunks, saved);
                    store.phase(lease, "INDEXING");
                    // ES响应丢失或部分成功时，以搜索事实确定缺失项；同ID补写不会重新生成向量。
                    var present = batchTrace.call("DATA", "es_present", () -> index.present(indexed));
                    var missing = indexed.stream().filter(c -> !present.contains(c.chunk().chunkId())).toList();
                    if (!missing.isEmpty()) {
                        current(lease);
                        batchTrace.call("DATA", "es_index", () -> {
                            index.index(missing);
                            return null;
                        });
                    }
                    index.verify(indexed);
                    store.indexed(lease, plan.ordinal());
                } catch (RuntimeException failed) {
                    batchSpan.fail(failed);
                    throw failed;
                }
            }
        }
        store.phase(lease, "VERIFYING");
        // 最终逐批重查可见性，另核全集数量以发现额外索引项；不能仅按成功批次数激活。
        for (var plan : plans) {
            current(lease);
            index.verify(items(content.document(), lease, parsed.chunks().subList(plan.start(), plan.start() + plan.count()), store.batch(lease, plan.ordinal())));
        }
        index.verifyGeneration(lease.documentId(), lease.documentVersion(), lease.processingRevision(), parsed.chunks().size());
        current(lease);
        trace.call("PUBLISH", "activate", () -> {
            store.activate(lease, parsed.chunks().size());
            return null;
        });
    }

    /**
     * 每批前复核当前身份、内容与租约，旧版本不可再发送到模型或开始ES写入。
     */
    private DocumentContent current(IngestionLease lease) {
        if (!store.renew(lease)) throw new LabException("STALE_EXECUTION", "入库执行权或累计期限已失效");
        var content = knowledge.document(lease.actor(), ScopeRequest.self(), lease.documentId());
        if (content.document().documentVersion() != lease.documentVersion())
            throw new LabException("STALE_EXECUTION", "内容版本已变化");
        return content;
    }

    /**
     * 先按32项和16000保守词元双限规划，摘要含稳定ID、实际输入hash和计数策略。
     */
    static List<IngestionBatchPlan> plans(IngestionLease lease, ParsedDocument parsed) {
        var result = new ArrayList<IngestionBatchPlan>();
        long total = 0;
        for (int start = 0; start < parsed.chunks().size(); ) {
            int end = start, tokens = 0;
            var summary = new StringBuilder(parsed.configHash());
            while (end < parsed.chunks().size() && end - start < 32) {
                var c = parsed.chunks().get(end);
                int size = TextWindow.count(c.embeddingText());
                if (size > 16000) throw new LabException("INGESTION_BUDGET_EXCEEDED", "单片超过模型批次限额");
                if (tokens + size > 16000) break;
                tokens += size;
                summary.append('|').append(c.chunkId()).append(':').append(hash(c.embeddingText()));
                end++;
            }
            total += tokens;
            String input = hash(summary.toString());
            String key = hash(lease.ingestionId() + ":" + result.size() + ":" + input);
            result.add(new IngestionBatchPlan(result.size(), start, end - start, key, input, tokens));
            start = end;
        }
        if (total > 2500000 || result.size() > 160)
            throw new LabException("INGESTION_BUDGET_EXCEEDED", "全文计划超过累计限额");
        return List.copyOf(result);
    }

    /**
     * 只组装单批向量，并检查恢复事实与计划数量匹配。
     */
    private List<IndexedChunk> items(DocumentSnapshot doc, IngestionLease l, List<ChunkSnapshot> chunks, IngestionBatch saved) {
        if (saved.vectors().size() != chunks.size())
            throw new LabException("INGESTION_PLAN_CONFLICT", "持久向量数量不符");
        var result = new ArrayList<IndexedChunk>();
        for (int i = 0; i < chunks.size(); i++)
            result.add(new IndexedChunk(doc.knowledgeBaseId(), doc.ownerUserId(), l.documentId(), l.documentVersion(), l.processingRevision(), chunks.get(i), saved.vectors().get(i), saved.modelVersion()));
        return result;
    }

    /**
     * 摘要仅用于稳定计划核验，不对外暴露原始模型输入。
     */
    private static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
