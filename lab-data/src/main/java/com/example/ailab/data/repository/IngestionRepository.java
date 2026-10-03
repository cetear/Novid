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
        var ids = sql.jdbc.query("SELECT i.id FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN users u ON u.id=i.actor_user_id JOIN knowledge_bases k ON k.id=d.knowledge_base_id WHERE d.deleted=FALSE AND k.enabled=TRUE AND k.deleted=FALSE AND u.enabled=TRUE AND u.password_change_required=FALSE AND d.current_version=i.document_version AND i.attempt<3 AND i.processing_revision=(SELECT MAX(newest.processing_revision) FROM document_ingestions newest WHERE newest.document_id=i.document_id AND newest.document_version=i.document_version) AND i.next_attempt_at<=CURRENT_TIMESTAMP(6) AND (i.status='RECEIVED' OR i.status='FAILED' OR i.status='PROCESSING' AND i.lease_until<CURRENT_TIMESTAMP(6)) ORDER BY i.id LIMIT 1 FOR UPDATE SKIP LOCKED", (r, n) -> r.getLong(1));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        sql.jdbc.update("UPDATE document_ingestions SET status='PROCESSING',attempt=attempt+1,worker_id=?,fencing_token=fencing_token+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?", worker, id);
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

    /**
     * ES 全集校验完成后才能调用本方法，原子切换 active revision。
     */
    @Transactional
    public void activate(IngestionLease l, int count) {
        valid(l);
        int actual = sql.jdbc.queryForObject("SELECT COUNT(*) FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=?", Integer.class, l.documentId(), l.documentVersion(), l.processingRevision());
        if (actual != count || count == 0) throw new LabException("CONTEXT_MAPPING_INVALID", "批次切片不完整");
        sql.jdbc.update("UPDATE document_versions SET active_processing_revision=?,ingestion_status='READY' WHERE document_id=? AND document_version=?", l.processingRevision(), l.documentId(), l.documentVersion());
        sql.jdbc.update("UPDATE document_ingestions SET status='READY',lease_until=NULL,error_code=NULL WHERE id=?", l.ingestionId());
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
            return;
        }
        sql.jdbc.update("UPDATE document_ingestions SET status='FAILED',error_code=?,lease_until=NULL,next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE id=?", code, l.ingestionId());
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

    /**
     * 当前内容、最新意图、未到期租约和 fencing 共同校验。
     */
    private void valid(IngestionLease l) {
        sql.actor(l.actor(), true);
        var count = sql.jdbc.queryForObject("SELECT COUNT(*) FROM document_ingestions i JOIN documents d ON d.id=i.document_id JOIN knowledge_bases k ON k.id=d.knowledge_base_id WHERE i.id=? AND i.worker_id=? AND i.fencing_token=? AND i.status='PROCESSING' AND i.lease_until>CURRENT_TIMESTAMP(6) AND d.current_version=i.document_version AND d.deleted=FALSE AND k.deleted=FALSE AND k.enabled=TRUE AND i.processing_revision=(SELECT MAX(j.processing_revision) FROM document_ingestions j WHERE j.document_id=i.document_id AND j.document_version=i.document_version)", Integer.class, l.ingestionId(), l.workerId(), l.fencingToken());
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
