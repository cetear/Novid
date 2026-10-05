package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.IngestionMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentIngestionStorePort;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * MySQL 有限入库队列；租约、版本和 fencing 共同保护激活。
 */
@Repository
public class IngestionRepository implements DocumentIngestionStorePort {
    private final IngestionMapper mapper;
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 复用权威原文端口，远程操作由 AI 编排在事务外执行。
     */
    public IngestionRepository(SqlSupport sql, DocumentSqlRepository docs) {
        this.sql = sql; this.mapper = sql.mapper(IngestionMapper.class);
        this.docs = docs;
    }

    /**
     * 使用 SKIP LOCKED 领取；过期租约只能在三次以内恢复。
     */
    @Transactional
    public Optional<IngestionLease> claim(String worker) {
        sql.scalar(mapper.claimSystemControlSelect(new Object[]{}), Integer.class);
        // 发送窗口崩溃且无提供方查询能力时明确未知，不能靠重领重购。
        mapper.claimDocumentIngestionsWrite(new Object[]{});
        mapper.claimIngestionBatchesWrite(new Object[]{});
        // 耗尽的代次明确失败；保留向量及尝试事实，不留下永远无法领取的处理中状态。
        mapper.claimDocumentIngestionsWrite2(new Object[]{});
        var ids = sql.project(mapper.claimDocumentIngestionsSelect(new Object[]{}), (r, n) -> r.longValue(1));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        mapper.claimDocumentIngestionsWrite3(new Object[]{worker, id});
        return sql.project(mapper.claimDocumentIngestionsSelect2(new Object[]{id}), (r, n) -> new IngestionLease(id, r.longValue("document_id"), r.intValue("document_version"), r.longValue("processing_revision"), new UserContext(r.longValue("actor_user_id"), UserContext.Role.valueOf(r.string("role")), r.booleanValue("enabled"), r.longValue("permission_version"), r.booleanValue("password_change_required")), worker, r.longValue("fencing_token"), r.string("title"), r.string("format"), r.string("raw_text"))).stream().findFirst();
    }

    /**
     * 续租复核当前用户，禁止降级／禁用后继续新的操作。
     */
    @Transactional
    public boolean renew(IngestionLease l) {
        try {
            valid(l);
            return mapper.renewDocumentIngestionsWrite(new Object[]{l.ingestionId(), l.workerId(), l.fencingToken()}) == 1;
        } catch (LabException e) {
            return false;
        }
    }

    /**
     * 结构保存与批次清理仅影响当前未激活 revision。
     */
    @Transactional
    public void saveStructure(IngestionLease l, ParsedDocument parsed) {
        valid(l);
        if (parsed.chunks().isEmpty() || parsed.chunks().size() > 5000)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "切片数量不合法");
        String existing = sql.scalar(mapper.saveStructureDocumentIngestionsSelect(new Object[]{l.ingestionId()}), String.class);
        if (existing != null) {
            // 恢复仅复用同一配置及完整ID/hash；禁止重解析覆盖已付费输入。
            var hashes = sql.project(mapper.saveStructureChunksSelect(new Object[]{l.documentId(), l.documentVersion(), l.processingRevision()}), (r,n) -> Map.entry(r.string(1),r.string(2)));
            var expected = parsed.chunks().stream().map(c -> Map.entry(c.chunkId(),c.chunkHash())).collect(java.util.stream.Collectors.toSet());
            if (!existing.equals(parsed.configHash()) || hashes.size()!=expected.size() || !expected.equals(new HashSet<>(hashes)))
                throw new LabException("INGESTION_PLAN_CONFLICT", "恢复配置或输入与持久事实不同，请明确新建处理代次");
            return;
        }
        mapper.saveStructureChunksWrite(new Object[]{l.documentId(), l.documentVersion(), l.processingRevision()});
        mapper.saveStructureContextParentsWrite(new Object[]{l.documentId(), l.documentVersion(), l.processingRevision()});
        mapper.saveStructureDocumentSectionsWrite(new Object[]{l.documentId(), l.documentVersion(), l.processingRevision()});
        for (var s : parsed.sections())
            mapper.saveStructureDocumentSectionsWrite2(new Object[]{s.sectionId(), l.documentId(), l.documentVersion(), l.processingRevision(), s.parentSectionId(), encode(s.ancestorSectionIds()), s.headingPath(), s.ordinal(), s.startOffset(), s.endOffset()});
        for (var p : parsed.parents())
            mapper.saveStructureContextParentsWrite2(new Object[]{p.contextParentId(), l.documentId(), l.documentVersion(), l.processingRevision(), p.sectionId(), p.ordinal(), p.startOffset(), p.endOffset()});
        for (var c : parsed.chunks())
            mapper.saveStructureChunksWrite2(new Object[]{c.chunkId(), l.documentId(), l.documentVersion(), l.processingRevision(), c.sectionId(), c.contextParentId(), c.chunkIndexInSection(), c.chunkIndexInParent(), c.startOffset(), c.endOffset(), c.rawText(), c.embeddingText(), c.chunkHash(), c.blockType(), c.blockId(), c.partIndex(), encode(c.sourceMap()), c.tokenCount(), c.countSource()});
        // 处理事实由已保存的新映射产生；旧批次不回填假解析版本。
        mapper.saveStructureDocumentIngestionsWrite(new Object[]{parsed.chunks().size(), parsed.configHash(), parsed.parserVersion(), parsed.splitPolicyVersion(), parsed.mappingVersion(), parsed.tokenizerRef(), parsed.countSource(), l.ingestionId()});
    }

    /** 计划只首次写入，顺序、摘要与额度在数据端再次守住。 */
    @Transactional
    public void plan(IngestionLease l, List<IngestionBatchPlan> plans) {
        valid(l);
        int expected=sql.scalar(mapper.planDocumentIngestionsSelect(new Object[]{l.ingestionId()}), Integer.class);
        int offset=0;
        long total=0;
        for (int i=0;i<plans.size();i++) {
            var p=plans.get(i);
            if(p.ordinal()!=i || p.start()!=offset || p.count()<1 || p.count()>32 || p.inputTokens()<1 || p.inputTokens()>16000)
                throw new LabException("INGESTION_PLAN_CONFLICT","批次计划不连续或超过限额");
            offset+=p.count();total+=p.inputTokens();
        }
        if(offset!=expected || total>2500000 || plans.size()>160) throw new LabException("INGESTION_BUDGET_EXCEEDED","全文计划超过入库预算");
        var old=sql.project(mapper.planIngestionBatchesSelect(new Object[]{l.ingestionId()}), (r,n)->readPlan(r));
        if(!old.isEmpty()) {
            if(!old.equals(plans)) throw new LabException("INGESTION_PLAN_CONFLICT","批次计划不能变化");
            return;
        }
        for(var p:plans) mapper.planIngestionBatchesWrite(new Object[]{l.ingestionId(), p.ordinal(), p.start(), p.count(), p.batchKey(), p.inputHash(), p.inputTokens()});
        mapper.planDocumentIngestionsWrite(new Object[]{l.ingestionId()});
    }

    /** 读取单批，不通过SQL载入全文向量。 */
    @Transactional
    public IngestionBatch batch(IngestionLease l,int ordinal) {
        valid(l);
        return sql.one(sql.project(mapper.batchIngestionBatchesSelect(new Object[]{l.ingestionId(), ordinal}), (r,n)->new IngestionBatch(readPlan(r),r.string("state"),decodeVectors(r.string("vector_json")),r.string("model_version"))));
    }

    /** 预算来自可靠数据库事实，重启不能延长截止时间。 */
    @Transactional
    public IngestionBudget budget(IngestionLease l) {
        valid(l);
        return sql.one(sql.project(mapper.budgetDocumentIngestionsSelect(new Object[]{l.ingestionId()}), (r,n)->new IngestionBudget(r.timestamp(1).toInstant(),r.intValue(2),r.longValue(3))));
    }

    /** 实际尝试前短事务预留，发送意图与attempt记录一同提交。 */
    @Transactional
    public void beginEmbedding(IngestionLease l,int ordinal) {
        valid(l);
        var b=sql.one(mapper.beginEmbeddingIngestionBatchesSelect(new Object[]{l.ingestionId(), ordinal}));
        if(!"PLANNED".equals(b.get("state"))) throw new LabException("EMBEDDING_RESULT_UNKNOWN","已有发送或成功事实，不能重新生成");
        int input=((Number)b.get("input_tokens")).intValue();
        if(mapper.beginEmbeddingDocumentIngestionsWrite(new Object[]{input, l.ingestionId(), input})!=1)
            throw new LabException("INGESTION_BUDGET_EXCEEDED","累计入库尝试或输入预算耗尽");
        int number=sql.scalar(mapper.beginEmbeddingDocumentIngestionsSelect(new Object[]{l.ingestionId()}), Integer.class);
        mapper.beginEmbeddingIngestionModelAttemptsWrite(new Object[]{l.ingestionId(), number, ordinal, input});
        mapper.beginEmbeddingIngestionBatchesWrite(new Object[]{l.ingestionId(), ordinal});
    }

    /** 付费成功先落盘，后续ES故障复用这些向量；非法响应不保存成功。 */
    @Transactional
    public void completeEmbedding(IngestionLease l,int ordinal,List<List<Float>> vectors,String model,Integer actual) {
        try { valid(l); }
        catch(LabException stale) {
            // 迟到响应只结算其原发送意图的已知用量，不保存向量、不赋予索引或激活权。
            if(actual!=null && actual>=0) {
                int updated=mapper.completeEmbeddingIngestionModelAttemptsWrite(new Object[]{actual, l.ingestionId(), ordinal});
                if(updated==1) mapper.completeEmbeddingDocumentIngestionsWrite(new Object[]{actual, l.ingestionId()});
            }
            return;
        }
        var b=sql.one(mapper.completeEmbeddingIngestionBatchesSelect(new Object[]{l.ingestionId(), ordinal}));
        int count=((Number)b.get("item_count")).intValue();
        if(!"SENDING".equals(b.get("state")) || vectors.size()!=count || model==null || model.isBlank() || model.length()>200 || actual!=null && actual<0)
            throw new LabException("MODEL_INVALID_OUTPUT","向量结果与发送意图不符");
        int dimensions=vectors.isEmpty()?0:vectors.get(0).size();
        if(dimensions<1 || dimensions>4096 || vectors.stream().anyMatch(v->v.size()!=dimensions || v.stream().anyMatch(x->x==null || !Float.isFinite(x))))
            throw new LabException("MODEL_INVALID_OUTPUT","向量维度或数值非法");
        mapper.completeEmbeddingIngestionBatchesWrite(new Object[]{encode(vectors), model, l.ingestionId(), ordinal});
        mapper.completeEmbeddingIngestionModelAttemptsWrite2(new Object[]{actual, l.ingestionId(), ordinal});
        mapper.completeEmbeddingDocumentIngestionsWrite2(new Object[]{actual==null?0:actual, actual==null?0:1, count, l.ingestionId()});
    }

    /** 只允许受控阶段，失败时保存最后实际执行阶段。 */
    @Transactional
    public void phase(IngestionLease l,String phase) {
        valid(l);
        if(!Set.of("PARSING","CHUNKING","EMBEDDING","INDEXING","VERIFYING").contains(phase)) throw LabException.invalid("未知入库阶段");
        mapper.phaseDocumentIngestionsWrite(new Object[]{phase, l.ingestionId()});
    }

    /** 只有向量事实存在且搜索验证成功才推进索引状态。 */
    @Transactional
    public void indexed(IngestionLease l,int ordinal) {
        valid(l);
        if(mapper.indexedIngestionBatchesWrite(new Object[]{l.ingestionId(), ordinal})!=1)
            throw new LabException("INDEX_NOT_READY","批次没有完整向量事实");
    }

    /** 稳定计划的标量字段不包含用户正文。 */
    private IngestionBatchPlan readPlan(SqlRow r) {
        return new IngestionBatchPlan(r.intValue("ordinal"),r.intValue("start_index"),r.intValue("item_count"),r.string("batch_key"),r.string("input_hash"),r.intValue("input_tokens"));
    }

    /** 固定浮点数组解析，不允许JSON多态对象。 */
    private List<List<Float>> decodeVectors(String value) {
        if(value==null) return List.of();
        try { return json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<List<Float>>>(){}); }
        catch(Exception e) { throw new LabException("INGESTION_PLAN_CONFLICT","持久向量损坏"); }
    }

    /**
     * ES 全集校验完成后才能调用本方法，原子切换 active revision。
     */
    @Transactional
    public void activate(IngestionLease l, int count) {
        valid(l);
        int actual = sql.scalar(mapper.activateChunksSelect(new Object[]{l.documentId(), l.documentVersion(), l.processingRevision()}), Integer.class);
        if (actual != count || count == 0) throw new LabException("CONTEXT_MAPPING_INVALID", "批次切片不完整");
        int verified = sql.scalar(mapper.activateIngestionBatchesSelect(new Object[]{l.ingestionId()}), Integer.class);
        if (verified!=count) throw new LabException("INDEX_NOT_READY", "持久批次未全部验证");
        mapper.activateDocumentVersionsWrite(new Object[]{l.processingRevision(), l.documentId(), l.documentVersion()});
        mapper.activateDocumentIngestionsWrite(new Object[]{l.ingestionId()});
        // 激活与清理意图同事务；只清理严格更旧的内容版本／代次，保留本次及后续批次。
        sql.event("PRUNE_DOCUMENT", l.documentId(), l.ingestionId());
        mapper.activateOutboxEventsWrite(new Object[]{l.documentId(), l.documentVersion()});
        sql.changed();
    }

    /**
     * 失败有界重试，不重置 attempt；过期执行者不覆盖当前事实。
     */
    @Transactional
    public void fail(IngestionLease l, String code) {
        try {
            valid(l);
        } catch (LabException stale) {
            // 仅收尾本租约，迟到旧执行者绝不更改后来领取者的状态。
            mapper.failDocumentIngestionsWrite(new Object[]{l.ingestionId(), l.workerId(), l.fencingToken()});
            return;
        }
        int sending = sql.scalar(mapper.failIngestionBatchesSelect(new Object[]{l.ingestionId()}), Integer.class);
        mapper.failIngestionBatchesWrite(new Object[]{l.ingestionId()});
        boolean retry = sending==0 && Set.of("SEARCH_UNAVAILABLE","INDEX_NOT_READY","RATE_LIMITED","INGESTION_FAILED").contains(code);
        mapper.failDocumentIngestionsWrite2(new Object[]{sending>0?"EMBEDDING_RESULT_UNKNOWN":code, sending>0, retry, l.ingestionId()});
        mapper.failDocumentVersionsWrite(new Object[]{l.documentId(), l.documentVersion()});
    }

    /**
     * 技术重处理创建新 revision，用户原文版本不改变。
     */
    @Transactional
    public void reprocess(UserContext actor, long id) {
        sql.actor(actor, true);
        var d = docs.metadata(id);
        sql.owner(actor, d.knowledgeBaseId(), true);
        long revision = sql.scalar(mapper.reprocessDocumentIngestionsSelect(new Object[]{id, d.documentVersion()}), Long.class);
        mapper.reprocessDocumentIngestionsWrite(new Object[]{id, d.documentVersion(), revision, actor.userId()});
    }

    /** 恢复必须锁定本人当前有效资料，只有最新且可恢复的失败代次可提前调度。 */
    @Transactional
    public void recover(UserContext actor,long id,long revision) {
        sql.actor(actor,true);var d=docs.metadata(id);sql.owner(actor,d.knowledgeBaseId(),true);
        docs.read(new AuthorizedKnowledgeScope(actor,ScopeRequest.Mode.SELF,List.of(),null,java.time.Instant.now()),id);
        int updated=mapper.recoverDocumentIngestionsWrite(new Object[]{id, d.documentVersion(), revision, id, d.documentVersion()});
        if(updated!=1) throw new LabException("INGESTION_RECOVERY_CONFLICT","指定代次不可恢复，请查询最新入库事实");
    }

    /**
     * 当前内容、最新意图、未到期租约和 fencing 共同校验。
     */
    private void valid(IngestionLease l) {
        sql.actor(l.actor(), true);
        var count = sql.scalar(mapper.validDocumentIngestionsSelect(new Object[]{l.ingestionId(), l.documentId(), l.documentVersion(), l.processingRevision(), l.actor().userId(), l.workerId(), l.fencingToken()}), Integer.class);
        if (count != 1) throw new LabException("STALE_EXECUTION", "入库执行权已失效");
    }

    /**
     * 类型固定的数组 JSON，不保存任意 Java 类型。
     */
    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
