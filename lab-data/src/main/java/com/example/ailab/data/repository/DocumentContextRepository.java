package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.DocumentContextMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentContextPort;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.util.*;

/** 原文／结构只从 MySQL 授权读取；ES 的父段或邻接信息不能决定关系。 */
@Repository
public class DocumentContextRepository implements DocumentContextPort {
    private final DocumentContextMapper mapper;
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ContextPolicy policy;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 兼容独立诊断构造，正式装配必须使用应用传入的窄参数。 */
    public DocumentContextRepository(SqlSupport sql, DocumentSqlRepository docs) {
        this(sql, docs, new ContextPolicy(20,6,6,2000,1,4000));
    }
    /** 数据模块只接框架无关参数，不依赖 AI 模块。 */
    @org.springframework.beans.factory.annotation.Autowired
    public DocumentContextRepository(SqlSupport sql, DocumentSqlRepository docs, ContextPolicy policy) {
        this.sql = sql; this.mapper = sql.mapper(DocumentContextMapper.class); this.docs = docs; this.policy = policy;
    }
    /** 搜索与扩展共用应用装配的参数，不能在 ES 适配器另写候选默认值。 */
    public ContextPolicy contextPolicy() { return policy; }
    /** 目录只返回当前激活代次，短锁与权限撤销／内容修订采用同一顺序。 */
    @Transactional
    public List<SectionSnapshot> sections(AuthorizedKnowledgeScope scope, long id, int offset, int limit) {
        sql.actor(scope.actor(), true); var d = docs.read(scope, id).document();
        ready(d);
        return sql.project(mapper.sectionsDocumentSectionsSelect(new Object[]{id, d.documentVersion(), d.activeProcessingRevision(), limit, offset}), this::section);
    }
    /** 小片只返回当前版本，与原文位置一起交付；旧批次无映射仍保持 LEGACY。 */
    @Transactional
    public List<ChunkSnapshot> chunks(AuthorizedKnowledgeScope scope, long id, int offset, int limit) {
        sql.actor(scope.actor(), true); var d = docs.read(scope, id).document(); ready(d);
        return sql.project(mapper.chunksChunksSelect(new Object[]{id, d.documentVersion(), d.activeProcessingRevision(), limit, offset}), DocumentContextRepository::chunk);
    }
    /** 分页游标绑定显式版本／代次，父章节含全部子标题；每页硬限制不超过 4000 保守词元。 */
    @Transactional
    public SectionPage sectionPage(AuthorizedKnowledgeScope scope, long id, int version, long revision, String sectionId, Integer after, int maxTokens) {
        if (id <= 0 || version <= 0 || revision <= 0 || maxTokens < 4 || maxTokens > policy.maxEvidenceTokens()) throw LabException.invalid("章节分页参数超限");
        sql.actor(scope.actor(), true); var content = docs.read(scope, id); var d = content.document(); ready(d);
        if (d.documentVersion() != version || d.activeProcessingRevision() != revision) throw new LabException("CONTEXT_VERSION_CONFLICT", "文档版本或处理代次已变化，请重新加载目录");


        var s = sql.project(mapper.sectionPageDocumentSectionsSelect(new Object[]{id, version, revision}, sectionId), this::section).stream().findFirst().orElseThrow(() -> new LabException("CONTEXT_MAPPING_INVALID", "章节不属于指定版本和代次"));
        int start = after == null ? s.startOffset() : after;
        if (s.startOffset() < 0 || s.endOffset() > content.text().length() || !TextWindow.boundary(content.text(), s.startOffset()) || !TextWindow.boundary(content.text(), s.endOffset())) throw mappingInvalid();
        if (start < s.startOffset() || start > s.endOffset() || !TextWindow.boundary(content.text(), start)) throw LabException.invalid("章节游标不合法");
        int end = TextWindow.end(content.text(), start, s.endOffset(), maxTokens);
        String text = content.text().substring(start,end); boolean complete = end == s.endOffset();
        return new SectionPage(id,version,revision,s.sectionId(),s.headingPath(),s.startOffset(),s.endOffset(),start,end,text,
                complete ? null : end,complete,end,s.endOffset(),TextWindow.count(text),TextWindow.COUNT_SOURCE);
    }
    /** 最新处理意图可失败或未激活，明确同时返回当前已激活代次。 */
    @Transactional
    public IngestionMetadata ingestion(AuthorizedKnowledgeScope scope, long id) {
        sql.actor(scope.actor(), true); var d = docs.read(scope, id).document();
        // 累计执行事实属于本人管理状态，管理员跨库正文读取权不扩大到这些记录。
        if(d.ownerUserId()!=scope.actor().userId()) throw LabException.denied();
        return sql.project(mapper.ingestionDocumentIngestionsSelect(new Object[]{id, d.documentVersion()}), (r,n) ->
                new IngestionMetadata(id,d.documentVersion(),d.activeProcessingRevision(),r.longValue("id"),r.longValue("processing_revision"),r.string("status"),r.string("error_code"),r.intValue("expected_chunk_count"),r.string("config_hash"),r.string("parser_version"),r.string("split_policy_version"),r.string("mapping_version"),r.string("tokenizer_ref"),r.string("count_source"),progress(r))).stream().findFirst().orElseThrow(() -> new LabException("INDEX_NOT_READY", "没有入库意图"));
    }
    /** 有界聚合只返回计数，旧批次没有事实时保留零计数与空期限。 */
    private IngestionProgress progress(SqlRow r) {
        long id=r.longValue("id");
        var counts=sql.one(mapper.progressIngestionBatchesSelect(new Object[]{id}));
        var deadline=r.timestamp("execution_deadline");var next=r.timestamp("next_attempt_at");
        boolean retry="FAILED".equals(r.string("status")) && r.booleanValue("retryable") && r.intValue("attempt")<3
                && deadline!=null && deadline.toInstant().isAfter(java.time.Instant.now()) && ((Number)counts.get("unknown_count")).intValue()==0;
        return new IngestionProgress(deadline==null && "READY".equals(r.string("status"))?"LEGACY":r.string("phase"),r.string("failure_stage"),r.intValue("attempt"),r.intValue("model_attempts"),r.longValue("reserved_input_tokens"),r.longValue("actual_input_tokens"),r.intValue("unknown_usage_attempts"),deadline==null?null:deadline.toInstant(),next==null?null:next.toInstant(),retry,
                ((Number)counts.get("planned")).intValue(),((Number)counts.get("embedded")).intValue(),((Number)counts.get("indexed")).intValue(),((Number)counts.get("unknown_count")).intValue(),r.intValue("peak_vector_items"));
    }
    /** 覆盖读取每次在下一个目录标题处截页；绝对游标单调推进，根与子章节不会重复读。 */
    @Transactional
    public SectionPage documentPage(AuthorizedKnowledgeScope scope,long id,int version,long revision,int after,int maxTokens) {
        var page = sectionPage(scope,id,version,revision,null,after,maxTokens);
        var owner = sql.project(mapper.documentPageDocumentSectionsSelect(new Object[]{id, version, revision, after}), this::section).stream().findFirst().orElseThrow(this::mappingInvalid);
        Integer nextHeading = sql.scalar(mapper.documentPageDocumentSectionsSelect2(new Object[]{id, version, revision, owner.ordinal()}), Integer.class);
        int ownEnd = nextHeading == null ? page.sectionEndOffset() : nextHeading;
        int end = Math.min(page.endOffset(),ownEnd); String text = page.text().substring(0,end-after);
        boolean complete = end == page.remainingEndOffset();
        return new SectionPage(id,version,revision,owner.sectionId(),owner.headingPath(),owner.startOffset(),ownEnd,after,end,text,
                complete ? null : end,complete,end,page.remainingEndOffset(),TextWindow.count(text),TextWindow.COUNT_SOURCE);
    }
    /** 种子、父段、邻片所有查询均绑定文档／版本／代次／章节；不沿客户端或 ES 的关系 ID 扩权。 */
    @Transactional
    public List<EvidenceBundle> expand(AuthorizedKnowledgeScope scope, List<ChunkCandidate> candidates, int requestedBudget) {
        sql.actor(scope.actor(), true);
        int budget = Math.min(requestedBudget,policy.maxEvidenceTokens()), used = 0, validSeeds = 0;
        var grouped = new LinkedHashMap<String,EvidenceBundle>();
        for (var candidate : candidates.stream().limit(policy.retrievalPerRoute()*2L).toList()) {
            DocumentContent content;
            try { content = docs.read(scope,candidate.documentId()); }
            catch (LabException e) { if (e.code().equals("ACCESS_DENIED")) continue; throw e; }
            var d = content.document();
            if (d.documentVersion() != candidate.documentVersion() || !Objects.equals(d.activeProcessingRevision(),candidate.processingRevision())) continue;
            var seeds = sql.project(mapper.expandChunksSelect(new Object[]{candidate.chunkId(), candidate.documentId(), candidate.documentVersion(), candidate.processingRevision()}), DocumentContextRepository::chunk);
            if (seeds.isEmpty()) continue;
            var seed = seeds.get(0); validChunk(content.text(),seed);
            var section = sql.project(mapper.expandDocumentSectionsSelect(new Object[]{seed.sectionId(), d.id(), d.documentVersion(), candidate.processingRevision()}), this::section).stream().findFirst().orElseThrow(this::mappingInvalid);
            var parent = sql.project(mapper.expandContextParentsSelect(new Object[]{seed.contextParentId(), seed.sectionId(), d.id(), d.documentVersion(), candidate.processingRevision()}), (r,n) -> new ParentSnapshot(r.string("parent_id"),r.string("section_id"),r.intValue("ordinal"),r.intValue("start_offset"),r.intValue("end_offset"))).stream().findFirst().orElseThrow(this::mappingInvalid);
            if (seed.startOffset() < parent.startOffset() || seed.endOffset() > parent.endOffset() || parent.startOffset() < section.startOffset() || parent.endOffset() > section.endOffset()) throw mappingInvalid();
            if (validSeeds++ >= policy.candidateChunks()) break;
            String key = seed.contextParentId();
            if (grouped.containsKey(key) && !grouped.get(key).includedChunkIds().contains(seed.chunkId())) key += ":" + seed.chunkId();
            if (grouped.containsKey(key)) {
                var old = grouped.get(key); var matches = new ArrayList<>(old.matchedChunkIds()); if (!matches.contains(seed.chunkId())) matches.add(seed.chunkId());
                grouped.put(key,new EvidenceBundle(old.evidenceId(),d,candidate.processingRevision(),old.sectionId(),old.headingPath(),matches,old.includedChunkIds(),old.startOffset(),old.endOffset(),old.text())); continue;
            }
            if (grouped.size() >= policy.finalEvidence()) continue;
            var included = sql.project(mapper.expandChunksSelect2(new Object[]{seed.contextParentId(), seed.sectionId(), d.id(), d.documentVersion(), candidate.processingRevision()}), DocumentContextRepository::chunk);
            int start = parent.startOffset(), end = parent.endOffset();
            int size = evidenceSize(section.headingPath(),content.text(),start,end);
            if (size > policy.parentMaxTokens() || used+size > budget) {
                included = sql.project(mapper.expandChunksSelect3(new Object[]{seed.sectionId(), d.id(), d.documentVersion(), candidate.processingRevision(), Math.max(0,seed.chunkIndexInSection()-policy.neighborWindow()), seed.chunkIndexInSection()+policy.neighborWindow()}), DocumentContextRepository::chunk);
                start = included.stream().mapToInt(ChunkSnapshot::startOffset).min().orElse(seed.startOffset()); end = included.stream().mapToInt(ChunkSnapshot::endOffset).max().orElse(seed.endOffset());
                size = evidenceSize(section.headingPath(),content.text(),start,end);
                if (used+size > budget) { included = List.of(seed); start = seed.startOffset(); end = seed.endOffset(); size = evidenceSize(section.headingPath(),content.text(),start,end); }
            }
            validateIncluded(content.text(),included,seed,start,end);
            // 检索重复的表头也要真实进入模型证据；不连续的表头作为独立原文包，不能伪装成一段连续正文。
            var headers=new LinkedHashMap<String,EvidenceBundle>(); int headerCost=0;
            for(var chunk:included) for(var map:chunk.sourceMap()) {
                if(!map.repeatedHeader() || map.sourceStartOffset()>=start && map.sourceEndOffset()<=end) continue;
                String headerKey="header:"+map.blockId();
                if(headers.containsKey(headerKey) || grouped.values().stream().anyMatch(e -> e.document().id()==d.id() && e.startOffset()<=map.sourceStartOffset() && e.endOffset()>=map.sourceEndOffset())) continue;
                var headerChunks=sql.project(mapper.expandChunksSelect4(new Object[]{d.id(), d.documentVersion(), candidate.processingRevision(), seed.sectionId(), map.sourceEndOffset(), map.sourceStartOffset()}), DocumentContextRepository::chunk);
                if(headerChunks.isEmpty()) throw mappingInvalid();
                headerChunks.forEach(c -> validChunk(content.text(),c));
                headerCost+=evidenceSize(section.headingPath(),content.text(),map.sourceStartOffset(),map.sourceEndOffset());
                headers.put(headerKey,new EvidenceBundle("header",d,candidate.processingRevision(),seed.sectionId(),section.headingPath(),List.of(),headerChunks.stream().map(ChunkSnapshot::chunkId).toList(),map.sourceStartOffset(),map.sourceEndOffset(),content.text().substring(map.sourceStartOffset(),map.sourceEndOffset())));
            }
            if(used+size+headerCost>budget || grouped.size()+1+headers.size()>policy.finalEvidence()) continue;
            if (used+size > budget) continue;
            used += size+headerCost;
            grouped.put(key,new EvidenceBundle("E"+(grouped.size()+1),d,candidate.processingRevision(),seed.sectionId(),section.headingPath(),List.of(seed.chunkId()),included.stream().map(ChunkSnapshot::chunkId).toList(),start,end,content.text().substring(start,end)));
            grouped.putAll(headers);
        }
        var bundles=new ArrayList<EvidenceBundle>();
        for(var bundle:grouped.values()) bundles.add(new EvidenceBundle("E"+(bundles.size()+1),bundle.document(),bundle.processingRevision(),bundle.sectionId(),bundle.headingPath(),bundle.matchedChunkIds(),bundle.includedChunkIds(),bundle.startOffset(),bundle.endOffset(),bundle.text()));
        return List.copyOf(bundles);
    }
    /** 实际送模包括完整标题和引用标记，计入同一个保守硬额度。 */
    private int evidenceSize(String heading,String text,int start,int end) {
        if (!TextWindow.boundary(text,start) || !TextWindow.boundary(text,end) || start > end) throw mappingInvalid();
        return TextWindow.count(heading)+TextWindow.count(text.substring(start,end))+64;
    }
    /** 邻片必须含种子、原文顺序和可信连续序号；空白间隙可保留，不能隐藏未读正文。 */
    private void validateIncluded(String text,List<ChunkSnapshot> chunks,ChunkSnapshot seed,int start,int end) {
        if (chunks.stream().noneMatch(c -> c.chunkId().equals(seed.chunkId()))) throw mappingInvalid();
        if (chunks.isEmpty() || chunks.get(0).startOffset()>start && !text.substring(start,chunks.get(0).startOffset()).isBlank()
                || chunks.get(chunks.size()-1).endOffset()<end && !text.substring(chunks.get(chunks.size()-1).endOffset(),end).isBlank()) throw mappingInvalid();
        ChunkSnapshot previous = null;
        for (var c : chunks) {
            validChunk(text,c);
            if (!c.sectionId().equals(seed.sectionId())) throw mappingInvalid();
            if (previous != null && (c.chunkIndexInSection() != previous.chunkIndexInSection()+1 || c.startOffset() < previous.startOffset()
                    || c.startOffset() > previous.endOffset() && !text.substring(previous.endOffset(),c.startOffset()).isBlank())) throw mappingInvalid();
            previous = c;
        }
    }
    /** hash 与 rawText 必须来自当前原文；关系正确也不能接受被篡改的正文。 */
    private void validChunk(String text,ChunkSnapshot c) {
        if (!TextWindow.boundary(text,c.startOffset()) || !TextWindow.boundary(text,c.endOffset()) || c.startOffset() >= c.endOffset()
                || !text.substring(c.startOffset(),c.endOffset()).equals(c.rawText()) || !SqlSupport.hash(c.embeddingText()).equals(c.chunkHash())) throw mappingInvalid();
        for (var m : c.sourceMap()) {
            if (!TextWindow.boundary(text,m.sourceStartOffset()) || !TextWindow.boundary(text,m.sourceEndOffset()) || m.sourceStartOffset()>m.sourceEndOffset()
                    || !TextWindow.boundary(c.embeddingText(),m.embeddingStartOffset()) || !TextWindow.boundary(c.embeddingText(),m.embeddingEndOffset())
                    || m.embeddingStartOffset()>m.embeddingEndOffset() || !text.substring(m.sourceStartOffset(),m.sourceEndOffset()).equals(c.embeddingText().substring(m.embeddingStartOffset(),m.embeddingEndOffset()))) throw mappingInvalid();
        }
    }
    /** 无激活批次明确失败，不借用旧版结构。 */
    private void ready(DocumentSnapshot d) { if (d.activeProcessingRevision() == null) throw new LabException("INDEX_NOT_READY","当前版本尚未完成索引"); }
    /** 返回稳定错误，不泄露损坏的数据行或内部查询。 */
    private LabException mappingInvalid() { return new LabException("CONTEXT_MAPPING_INVALID","原文或结构关系损坏"); }
    /** 章节祖先只解码固定字符串列表。 */
    private SectionSnapshot section(SqlRow r,int n) {
        return new SectionSnapshot(r.string("section_id"),r.string("parent_section_id"),decode(r.string("ancestor_json"),new TypeReference<List<String>>(){}),r.string("heading_path"),r.intValue("ordinal"),r.intValue("start_offset"),r.intValue("end_offset"));
    }
    /** V6 空字段视为旧处理批次，无重新解析事实不补假映射。 */
    public static ChunkSnapshot chunk(SqlRow r,int n) {
        String embedding = r.string("embedding_text"), sourceMap = r.string("source_map");
        return new ChunkSnapshot(r.string("chunk_id"),r.string("section_id"),r.string("parent_id"),r.intValue("index_in_section"),r.intValue("index_in_parent"),r.intValue("start_offset"),r.intValue("end_offset"),r.string("raw_text"),embedding,r.string("chunk_hash"),r.string("block_type"),r.string("block_id"),r.intValue("part_index"),
                sourceMap == null ? List.of() : decode(sourceMap,new TypeReference<List<TextMapping>>(){}),r.value("token_count") == null ? TextWindow.count(embedding) : r.intValue("token_count"),r.string("count_source") == null ? TextWindow.COUNT_SOURCE : r.string("count_source"));
    }
    /** 固定 DTO JSON，不启用多态 Java 类型。 */
    private static <T> T decode(String value,TypeReference<T> type) {
        try { return JSON.readValue(value,type); }
        catch (Exception e) { throw new LabException("CONTEXT_MAPPING_INVALID","结构映射损坏"); }
    }
}
