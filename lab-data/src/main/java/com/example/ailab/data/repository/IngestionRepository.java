package com.example.ailab.data.repository;

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
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * 复用权威原文端口，远程操作由 AI 编排在事务外执行。
     */
    public IngestionRepository(SqlSupport sql, DocumentSqlRepository docs) {
        this.sql = sql;
        this.docs = docs;
    }

    /**
     * 使用 SKIP LOCKED 领取；过期租约只能在三次以内恢复。
     */
    @Transactional
    public Optional<IngestionLease> claim(String worker) {
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE", Integer.class);
        // 发送窗口崩溃且无提供方查询能力时明确未知，不能靠重领重购。
        sql.jdbc.update("UPDATE document_ingestions i SET i.status='FAILED',i.retryable=FALSE,i.error_code='EMBEDDING_RESULT_UNKNOWN',i.failure_stage='EMBEDDING',i.lease_until=NULL WHERE i.status='PROCESSING' AND i.lease_until<CURRENT_TIMESTAMP(6) AND EXISTS(SELECT 1 FROM ingestion_batches b WHERE b.ingestion_id=i.id AND b.state='SENDING')");
        sql.jdbc.update("UPDATE ingestion_batches b JOIN document_ingestions i ON i.id=b.ingestion_id SET b.state='UNKNOWN' WHERE b.state='SENDING' AND i.error_code='EMBEDDING_RESULT_UNKNOWN'");
        // 耗尽的代次明确失败；保留向量及尝试事实，不留下永远无法领取的处理中状态。
        sql.jdbc.update("UPDATE document_ingestions SET status='FAILED',retryable=FALSE,error_code='INGESTION_BUDGET_EXCEEDED',failure_stage=phase,lease_until=NULL WHERE retryable=TRUE AND status IN ('RECEIVED','FAILED','PROCESSING') AND (execution_deadline<=CURRENT_TIMESTAMP(6) OR attempt>=3 AND (status<>'PROCESSING' OR lease_until<CURRENT_TIMESTAMP(6)))");
        var ids = sql.jdbc.query("SELECT i.id FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN users u ON u.id=i.actor_user_id JOIN knowledge_bases k ON k.id=d.knowledge_base_id WHERE d.deleted=FALSE AND k.enabled=TRUE AND k.deleted=FALSE AND u.enabled=TRUE AND u.password_change_required=FALSE AND d.current_version=i.document_version AND i.attempt<3 AND i.retryable=TRUE AND (i.execution_deadline IS NULL OR i.execution_deadline>CURRENT_TIMESTAMP(6)) AND i.processing_revision=(SELECT MAX(newest.processing_revision) FROM document_ingestions newest WHERE newest.document_id=i.document_id AND newest.document_version=i.document_version) AND i.next_attempt_at<=CURRENT_TIMESTAMP(6) AND (i.status='RECEIVED' OR i.status='FAILED' OR i.status='PROCESSING' AND i.lease_until<CURRENT_TIMESTAMP(6)) ORDER BY i.id LIMIT 1 FOR UPDATE SKIP LOCKED", (r, n) -> r.getLong(1));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        sql.jdbc.update("UPDATE document_ingestions SET status='PROCESSING',attempt=attempt+1,worker_id=?,fencing_token=fencing_token+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),execution_deadline=COALESCE(execution_deadline,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 600 SECOND)) WHERE id=?", worker, id);
        return sql.jdbc.query("SELECT i.*,d.title,d.format,v.raw_text,u.* FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN document_versions v ON v.document_id=i.document_id AND v.document_version=i.document_version JOIN users u ON u.id=i.actor_user_id WHERE i.id=?", (r, n) -> new IngestionLease(id, r.getLong("document_id"), r.getInt("document_version"), r.getLong("processing_revision"), new UserContext(r.getLong("actor_user_id"), UserContext.Role.valueOf(r.getString("role")), r.getBoolean("enabled"), r.getLong("permission_version"), r.getBoolean("password_change_required")), worker, r.getLong("fencing_token"), r.getString("title"), r.getString("format"), r.getString("raw_text")), id).stream().findFirst();
    }

    /**
     * 续租复核当前用户，禁止降级／禁用后继续新的操作。
     */
    @Transactional
    public boolean renew(IngestionLease l) {
        try {
            valid(l);
            return sql.jdbc.update("UPDATE document_ingestions SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=? AND worker_id=? AND fencing_token=?", l.ingestionId(), l.workerId(), l.fencingToken()) == 1;
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
        String existing = sql.jdbc.queryForObject("SELECT config_hash FROM document_ingestions WHERE id=?", String.class, l.ingestionId());
        if (existing != null) {
            // 恢复仅复用同一配置及完整ID/hash；禁止重解析覆盖已付费输入。
            var hashes = sql.jdbc.query("SELECT chunk_id,chunk_hash FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=?", (r,n) -> Map.entry(r.getString(1),r.getString(2)), l.documentId(),l.documentVersion(),l.processingRevision());
            var expected = parsed.chunks().stream().map(c -> Map.entry(c.chunkId(),c.chunkHash())).collect(java.util.stream.Collectors.toSet());
            if (!existing.equals(parsed.configHash()) || hashes.size()!=expected.size() || !expected.equals(new HashSet<>(hashes)))
                throw new LabException("INGESTION_PLAN_CONFLICT", "恢复配置或输入与持久事实不同，请明确新建处理代次");
            return;
        }
        sql.jdbc.update("DELETE FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=?", l.documentId(), l.documentVersion(), l.processingRevision());
        sql.jdbc.update("DELETE FROM context_parents WHERE document_id=? AND document_version=? AND processing_revision=?", l.documentId(), l.documentVersion(), l.processingRevision());
        sql.jdbc.update("DELETE FROM document_sections WHERE document_id=? AND document_version=? AND processing_revision=?", l.documentId(), l.documentVersion(), l.processingRevision());
        for (var s : parsed.sections())
            sql.jdbc.update("INSERT INTO document_sections VALUES(?,?,?,?,?,?,?,?,?,?)", s.sectionId(), l.documentId(), l.documentVersion(), l.processingRevision(), s.parentSectionId(), encode(s.ancestorSectionIds()), s.headingPath(), s.ordinal(), s.startOffset(), s.endOffset());
        for (var p : parsed.parents())
            sql.jdbc.update("INSERT INTO context_parents VALUES(?,?,?,?,?,?,?,?)", p.contextParentId(), l.documentId(), l.documentVersion(), l.processingRevision(), p.sectionId(), p.ordinal(), p.startOffset(), p.endOffset());
        for (var c : parsed.chunks())
            sql.jdbc.update("INSERT INTO chunks(chunk_id,document_id,document_version,processing_revision,section_id,parent_id,index_in_section,index_in_parent,start_offset,end_offset,raw_text,embedding_text,chunk_hash,block_type,block_id,part_index,source_map,token_count,count_source) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", c.chunkId(), l.documentId(), l.documentVersion(), l.processingRevision(), c.sectionId(), c.contextParentId(), c.chunkIndexInSection(), c.chunkIndexInParent(), c.startOffset(), c.endOffset(), c.rawText(), c.embeddingText(), c.chunkHash(), c.blockType(), c.blockId(), c.partIndex(), encode(c.sourceMap()), c.tokenCount(), c.countSource());
        // 处理事实由已保存的新映射产生；旧批次不回填假解析版本。
        sql.jdbc.update("UPDATE document_ingestions SET expected_chunk_count=?,config_hash=?,parser_version=?,split_policy_version=?,mapping_version=?,tokenizer_ref=?,count_source=? WHERE id=?", parsed.chunks().size(), parsed.configHash(), parsed.parserVersion(), parsed.splitPolicyVersion(), parsed.mappingVersion(), parsed.tokenizerRef(), parsed.countSource(), l.ingestionId());
    }

    /** 计划只首次写入，顺序、摘要与额度在数据端再次守住。 */
    @Transactional
    public void plan(IngestionLease l, List<IngestionBatchPlan> plans) {
        valid(l);
        int expected=sql.jdbc.queryForObject("SELECT expected_chunk_count FROM document_ingestions WHERE id=?",Integer.class,l.ingestionId());
        int offset=0;
        long total=0;
        for (int i=0;i<plans.size();i++) {
            var p=plans.get(i);
            if(p.ordinal()!=i || p.start()!=offset || p.count()<1 || p.count()>32 || p.inputTokens()<1 || p.inputTokens()>16000)
                throw new LabException("INGESTION_PLAN_CONFLICT","批次计划不连续或超过限额");
            offset+=p.count();total+=p.inputTokens();
        }
        if(offset!=expected || total>2500000 || plans.size()>160) throw new LabException("INGESTION_BUDGET_EXCEEDED","全文计划超过入库预算");
        var old=sql.jdbc.query("SELECT * FROM ingestion_batches WHERE ingestion_id=? ORDER BY ordinal",(r,n)->readPlan(r),l.ingestionId());
        if(!old.isEmpty()) {
            if(!old.equals(plans)) throw new LabException("INGESTION_PLAN_CONFLICT","批次计划不能变化");
            return;
        }
        for(var p:plans) sql.jdbc.update("INSERT INTO ingestion_batches(ingestion_id,ordinal,start_index,item_count,batch_key,input_hash,input_tokens) VALUES(?,?,?,?,?,?,?)",l.ingestionId(),p.ordinal(),p.start(),p.count(),p.batchKey(),p.inputHash(),p.inputTokens());
        sql.jdbc.update("UPDATE document_ingestions SET execution_deadline=COALESCE(execution_deadline,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 600 SECOND)) WHERE id=?",l.ingestionId());
    }

    /** 读取单批，不通过SQL载入全文向量。 */
    @Transactional
    public IngestionBatch batch(IngestionLease l,int ordinal) {
        valid(l);
        return sql.jdbc.queryForObject("SELECT * FROM ingestion_batches WHERE ingestion_id=? AND ordinal=?",(r,n)->new IngestionBatch(readPlan(r),r.getString("state"),decodeVectors(r.getString("vector_json")),r.getString("model_version")),l.ingestionId(),ordinal);
    }

    /** 预算来自可靠数据库事实，重启不能延长截止时间。 */
    @Transactional
    public IngestionBudget budget(IngestionLease l) {
        valid(l);
        return sql.jdbc.queryForObject("SELECT execution_deadline,model_attempts,reserved_input_tokens FROM document_ingestions WHERE id=?",(r,n)->new IngestionBudget(r.getTimestamp(1).toInstant(),r.getInt(2),r.getLong(3)),l.ingestionId());
    }

    /** 实际尝试前短事务预留，发送意图与attempt记录一同提交。 */
    @Transactional
    public void beginEmbedding(IngestionLease l,int ordinal) {
        valid(l);
        var b=sql.jdbc.queryForMap("SELECT * FROM ingestion_batches WHERE ingestion_id=? AND ordinal=?",l.ingestionId(),ordinal);
        if(!"PLANNED".equals(b.get("state"))) throw new LabException("EMBEDDING_RESULT_UNKNOWN","已有发送或成功事实，不能重新生成");
        int input=((Number)b.get("input_tokens")).intValue();
        if(sql.jdbc.update("UPDATE document_ingestions SET model_attempts=model_attempts+1,reserved_input_tokens=reserved_input_tokens+?,unknown_usage_attempts=unknown_usage_attempts+1,phase='EMBEDDING' WHERE id=? AND model_attempts<160 AND reserved_input_tokens+?<=2500000",input,l.ingestionId(),input)!=1)
            throw new LabException("INGESTION_BUDGET_EXCEEDED","累计入库尝试或输入预算耗尽");
        int number=sql.jdbc.queryForObject("SELECT model_attempts FROM document_ingestions WHERE id=?",Integer.class,l.ingestionId());
        sql.jdbc.update("INSERT INTO ingestion_model_attempts(ingestion_id,attempt_no,batch_ordinal,input_tokens) VALUES(?,?,?,?)",l.ingestionId(),number,ordinal,input);
        sql.jdbc.update("UPDATE ingestion_batches SET state='SENDING' WHERE ingestion_id=? AND ordinal=?",l.ingestionId(),ordinal);
    }

    /** 付费成功先落盘，后续ES故障复用这些向量；非法响应不保存成功。 */
    @Transactional
    public void completeEmbedding(IngestionLease l,int ordinal,List<List<Float>> vectors,String model,Integer actual) {
        try { valid(l); }
        catch(LabException stale) {
            // 迟到响应只结算其原发送意图的已知用量，不保存向量、不赋予索引或激活权。
            if(actual!=null && actual>=0) {
                int updated=sql.jdbc.update("UPDATE ingestion_model_attempts SET actual_input_tokens=?,result='LATE_SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6) WHERE ingestion_id=? AND batch_ordinal=? AND result='UNKNOWN'",actual,l.ingestionId(),ordinal);
                if(updated==1) sql.jdbc.update("UPDATE document_ingestions SET actual_input_tokens=actual_input_tokens+?,unknown_usage_attempts=unknown_usage_attempts-1 WHERE id=?",actual,l.ingestionId());
            }
            return;
        }
        var b=sql.jdbc.queryForMap("SELECT * FROM ingestion_batches WHERE ingestion_id=? AND ordinal=?",l.ingestionId(),ordinal);
        int count=((Number)b.get("item_count")).intValue();
        if(!"SENDING".equals(b.get("state")) || vectors.size()!=count || model==null || model.isBlank() || model.length()>200 || actual!=null && actual<0)
            throw new LabException("MODEL_INVALID_OUTPUT","向量结果与发送意图不符");
        int dimensions=vectors.isEmpty()?0:vectors.get(0).size();
        if(dimensions<1 || dimensions>4096 || vectors.stream().anyMatch(v->v.size()!=dimensions || v.stream().anyMatch(x->x==null || !Float.isFinite(x))))
            throw new LabException("MODEL_INVALID_OUTPUT","向量维度或数值非法");
        sql.jdbc.update("UPDATE ingestion_batches SET state='EMBEDDED',vector_json=?,model_version=? WHERE ingestion_id=? AND ordinal=?",encode(vectors),model,l.ingestionId(),ordinal);
        sql.jdbc.update("UPDATE ingestion_model_attempts SET result='SUCCEEDED',actual_input_tokens=?,completed_at=CURRENT_TIMESTAMP(6) WHERE ingestion_id=? AND batch_ordinal=? AND result='UNKNOWN'",actual,l.ingestionId(),ordinal);
        sql.jdbc.update("UPDATE document_ingestions SET actual_input_tokens=actual_input_tokens+?,unknown_usage_attempts=unknown_usage_attempts-?,peak_vector_items=GREATEST(peak_vector_items,?) WHERE id=?",actual==null?0:actual,actual==null?0:1,count,l.ingestionId());
    }

    /** 只允许受控阶段，失败时保存最后实际执行阶段。 */
    @Transactional
    public void phase(IngestionLease l,String phase) {
        valid(l);
        if(!Set.of("PARSING","CHUNKING","EMBEDDING","INDEXING","VERIFYING").contains(phase)) throw LabException.invalid("未知入库阶段");
        sql.jdbc.update("UPDATE document_ingestions SET phase=? WHERE id=?",phase,l.ingestionId());
    }

    /** 只有向量事实存在且搜索验证成功才推进索引状态。 */
    @Transactional
    public void indexed(IngestionLease l,int ordinal) {
        valid(l);
        if(sql.jdbc.update("UPDATE ingestion_batches SET state='INDEXED' WHERE ingestion_id=? AND ordinal=? AND state IN ('EMBEDDED','INDEXED')",l.ingestionId(),ordinal)!=1)
            throw new LabException("INDEX_NOT_READY","批次没有完整向量事实");
    }

    /** 稳定计划的标量字段不包含用户正文。 */
    private IngestionBatchPlan readPlan(java.sql.ResultSet r) throws java.sql.SQLException {
        return new IngestionBatchPlan(r.getInt("ordinal"),r.getInt("start_index"),r.getInt("item_count"),r.getString("batch_key"),r.getString("input_hash"),r.getInt("input_tokens"));
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
        int actual = sql.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=?", Integer.class, l.documentId(), l.documentVersion(), l.processingRevision());
        if (actual != count || count == 0) throw new LabException("CONTEXT_MAPPING_INVALID", "批次切片不完整");
        int verified = sql.jdbc.queryForObject("SELECT COALESCE(SUM(item_count),0) FROM ingestion_batches WHERE ingestion_id=? AND state='INDEXED'", Integer.class, l.ingestionId());
        if (verified!=count) throw new LabException("INDEX_NOT_READY", "持久批次未全部验证");
        sql.jdbc.update("UPDATE document_versions SET active_processing_revision=?,ingestion_status='READY' WHERE document_id=? AND document_version=?", l.processingRevision(), l.documentId(), l.documentVersion());
        sql.jdbc.update("UPDATE document_ingestions SET status='READY',phase='READY',lease_until=NULL,error_code=NULL,failure_stage=NULL,retryable=FALSE WHERE id=?", l.ingestionId());
        // 激活与清理意图同事务；只清理严格更旧的内容版本／代次，保留本次及后续批次。
        sql.event("PRUNE_DOCUMENT", l.documentId(), l.ingestionId());
        sql.jdbc.update("UPDATE outbox_events SET status='DONE' WHERE event_type='INGEST_DOCUMENT' AND resource_id=? AND resource_version=?", l.documentId(), l.documentVersion());
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
            sql.jdbc.update("UPDATE document_ingestions i JOIN documents d ON d.id=i.document_id JOIN knowledge_bases k ON k.id=d.knowledge_base_id SET i.status='FAILED',i.failure_stage=i.phase,i.lease_until=NULL,i.retryable=FALSE,i.error_code=CASE WHEN d.deleted THEN 'DOCUMENT_DELETED' WHEN k.deleted THEN 'LIBRARY_DELETED' WHEN NOT k.enabled THEN 'LIBRARY_DISABLED' WHEN d.current_version<>i.document_version THEN 'DOCUMENT_VERSION_CHANGED' WHEN i.execution_deadline<=CURRENT_TIMESTAMP(6) THEN 'INGESTION_BUDGET_EXCEEDED' ELSE 'STALE_EXECUTION' END WHERE i.id=? AND i.worker_id=? AND i.fencing_token=? AND i.status='PROCESSING'",l.ingestionId(),l.workerId(),l.fencingToken());
            return;
        }
        int sending = sql.jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_batches WHERE ingestion_id=? AND state IN ('SENDING','UNKNOWN')",Integer.class,l.ingestionId());
        sql.jdbc.update("UPDATE ingestion_batches SET state='UNKNOWN' WHERE ingestion_id=? AND state='SENDING'",l.ingestionId());
        boolean retry = sending==0 && Set.of("SEARCH_UNAVAILABLE","INDEX_NOT_READY","RATE_LIMITED","INGESTION_FAILED").contains(code);
        sql.jdbc.update("UPDATE document_ingestions SET status='FAILED',error_code=?,failure_stage=IF(?,'EMBEDDING',phase),retryable=?,lease_until=NULL,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE id=?",sending>0?"EMBEDDING_RESULT_UNKNOWN":code,sending>0,retry,l.ingestionId());
        sql.jdbc.update("UPDATE document_versions SET ingestion_status=IF(active_processing_revision IS NULL,'FAILED',ingestion_status) WHERE document_id=? AND document_version=?", l.documentId(), l.documentVersion());
    }

    /**
     * 技术重处理创建新 revision，用户原文版本不改变。
     */
    @Transactional
    public void reprocess(UserContext actor, long id) {
        sql.actor(actor, true);
        var d = docs.metadata(id);
        sql.owner(actor, d.knowledgeBaseId(), true);
        long revision = sql.jdbc.queryForObject("SELECT COALESCE(MAX(processing_revision),0)+1 FROM document_ingestions WHERE document_id=? AND document_version=?", Long.class, id, d.documentVersion());
        sql.jdbc.update("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id) VALUES(?,?,?,?)", id, d.documentVersion(), revision, actor.userId());
    }

    /** 恢复必须锁定本人当前有效资料，只有最新且可恢复的失败代次可提前调度。 */
    @Transactional
    public void recover(UserContext actor,long id,long revision) {
        sql.actor(actor,true);var d=docs.metadata(id);sql.owner(actor,d.knowledgeBaseId(),true);
        docs.read(new AuthorizedKnowledgeScope(actor,ScopeRequest.Mode.SELF,List.of(),null,java.time.Instant.now()),id);
        int updated=sql.jdbc.update("UPDATE document_ingestions i SET next_attempt_at=CURRENT_TIMESTAMP(6) WHERE i.document_id=? AND i.document_version=? AND i.processing_revision=? AND i.status='FAILED' AND i.retryable=TRUE AND i.attempt<3 AND i.execution_deadline>CURRENT_TIMESTAMP(6) AND NOT EXISTS(SELECT 1 FROM ingestion_batches b WHERE b.ingestion_id=i.id AND b.state IN ('SENDING','UNKNOWN')) AND i.processing_revision=(SELECT newest.rev FROM (SELECT MAX(processing_revision) rev FROM document_ingestions WHERE document_id=? AND document_version=?) newest)",id,d.documentVersion(),revision,id,d.documentVersion());
        if(updated!=1) throw new LabException("INGESTION_RECOVERY_CONFLICT","指定代次不可恢复，请查询最新入库事实");
    }

    /**
     * 当前内容、最新意图、未到期租约和 fencing 共同校验。
     */
    private void valid(IngestionLease l) {
        sql.actor(l.actor(), true);
        var count = sql.jdbc.queryForObject("SELECT COUNT(*) FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN knowledge_bases k ON k.id=d.knowledge_base_id WHERE i.id=? AND i.document_id=? AND i.document_version=? AND i.processing_revision=? AND i.actor_user_id=? AND i.worker_id=? AND i.fencing_token=? AND i.status='PROCESSING' AND i.lease_until>CURRENT_TIMESTAMP(6) AND (i.execution_deadline IS NULL OR i.execution_deadline>CURRENT_TIMESTAMP(6)) AND d.current_version=i.document_version AND d.deleted=FALSE AND k.deleted=FALSE AND k.enabled=TRUE AND d.owner_user_id=i.actor_user_id AND i.processing_revision=(SELECT MAX(j.processing_revision) FROM document_ingestions j WHERE j.document_id=i.document_id AND j.document_version=i.document_version)", Integer.class, l.ingestionId(), l.documentId(), l.documentVersion(), l.processingRevision(), l.actor().userId(), l.workerId(), l.fencingToken());
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
