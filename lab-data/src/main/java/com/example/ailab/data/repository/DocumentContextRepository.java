package com.example.ailab.data.repository;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentContextPort;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.util.*;
/** MySQL 原文及版本化章节／父段／小片；从不信任 ES 里的父段关系。 */
@Repository
public class DocumentContextRepository implements DocumentContextPort {
    private final SqlSupport sql;private final DocumentSqlRepository docs;private final ObjectMapper json=new ObjectMapper();
    /** 目录查询复用原文授权与来源规则。 */
    public DocumentContextRepository(SqlSupport sql,DocumentSqlRepository docs){this.sql=sql;this.docs=docs;}
    /** 仅返回当前已激活 revision 的目录。 */
    public List<SectionSnapshot> sections(AuthorizedKnowledgeScope scope,long id,int offset,int limit){
        var d=docs.read(scope,id).document();ready(d);
        return sql.jdbc.query("SELECT * FROM document_sections WHERE document_id=? AND document_version=? AND processing_revision=? ORDER BY ordinal LIMIT ? OFFSET ?",this::section,id,d.documentVersion(),d.activeProcessingRevision(),limit,offset);
    }
    /** 小片查询保持同一版本和处理代次。 */
    public List<ChunkSnapshot> chunks(AuthorizedKnowledgeScope scope,long id,int offset,int limit){
        var d=docs.read(scope,id).document();ready(d);
        return sql.jdbc.query("SELECT * FROM chunks WHERE document_id=? AND document_version=? AND processing_revision=? ORDER BY start_offset LIMIT ? OFFSET ?",DocumentContextRepository::chunk,id,d.documentVersion(),d.activeProcessingRevision(),limit,offset);
    }
    /** 扩展命中片所在真实父段，过大时退回前后一个同章节邻片。 */
    public List<EvidenceBundle> expand(AuthorizedKnowledgeScope scope,List<ChunkCandidate> candidates,int budget){
        var grouped=new LinkedHashMap<String,EvidenceBundle>();int used=0, validSeeds=0;
        for(var c:candidates.stream().limit(40).toList()){
            DocumentContent content;try{content=docs.read(scope,c.documentId());}catch(LabException e){if(e.code().equals("ACCESS_DENIED"))continue;throw e;}
            var d=content.document();if(d.documentVersion()!=c.documentVersion()||d.activeProcessingRevision()==null||d.activeProcessingRevision()!=c.processingRevision())continue;
            var found=sql.jdbc.query("SELECT * FROM chunks WHERE chunk_id=? AND document_id=? AND document_version=? AND processing_revision=?",DocumentContextRepository::chunk,c.chunkId(),c.documentId(),c.documentVersion(),c.processingRevision());
            if(found.isEmpty())continue;if(validSeeds++>=6)break;var seed=found.get(0);String key=seed.contextParentId();
            if(grouped.containsKey(key)&&!grouped.get(key).includedChunkIds().contains(seed.chunkId()))key=key+":"+seed.chunkId();
            if(grouped.containsKey(key)){var old=grouped.get(key);var matches=new ArrayList<>(old.matchedChunkIds());if(!matches.contains(seed.chunkId()))matches.add(seed.chunkId());grouped.put(key,new EvidenceBundle(old.evidenceId(),d,c.processingRevision(),old.sectionId(),old.headingPath(),matches,old.includedChunkIds(),old.startOffset(),old.endOffset(),old.text()));continue;}
            var neighbors=sql.jdbc.query("SELECT * FROM chunks WHERE parent_id=? ORDER BY index_in_parent",DocumentContextRepository::chunk,seed.contextParentId());
            int start=neighbors.stream().mapToInt(ChunkSnapshot::startOffset).min().orElse(seed.startOffset()),end=neighbors.stream().mapToInt(ChunkSnapshot::endOffset).max().orElse(seed.endOffset());
            String heading=sql.jdbc.queryForObject("SELECT heading_path FROM document_sections WHERE section_id=?",String.class,seed.sectionId());
            int size=bytes(heading)+bytes(content.text().substring(start,end))+64;
            if(size>2000||used+size>budget){
                neighbors=sql.jdbc.query("SELECT * FROM chunks WHERE section_id=? AND index_in_section BETWEEN ? AND ? ORDER BY index_in_section",DocumentContextRepository::chunk,seed.sectionId(),Math.max(0,seed.chunkIndexInSection()-1),seed.chunkIndexInSection()+1);
                start=neighbors.stream().mapToInt(ChunkSnapshot::startOffset).min().orElse(seed.startOffset());end=neighbors.stream().mapToInt(ChunkSnapshot::endOffset).max().orElse(seed.endOffset());size=bytes(heading)+bytes(content.text().substring(start,end))+64;
                if(used+size>budget){neighbors=List.of(seed);start=seed.startOffset();end=seed.endOffset();size=bytes(heading)+bytes(seed.rawText())+64;}
            }
            if(used+size>budget)continue;used+=size;
            grouped.put(key,new EvidenceBundle("E"+(grouped.size()+1),d,c.processingRevision(),seed.sectionId(),heading,List.of(seed.chunkId()),neighbors.stream().map(ChunkSnapshot::chunkId).toList(),start,end,content.text().substring(start,end)));
        }
        return List.copyOf(grouped.values());
    }
    /** 验证当前内容已就绪，不借用旧版本回答。 */
    private void ready(DocumentSnapshot d){if(d.activeProcessingRevision()==null)throw new LabException("INDEX_NOT_READY","当前版本尚未完成索引");}
    /** 数据行转目录快照。 */
    private SectionSnapshot section(ResultSet r,int n)throws SQLException{return new SectionSnapshot(r.getString("section_id"),r.getString("parent_section_id"),decode(r.getString("ancestor_json")),r.getString("heading_path"),r.getInt("ordinal"),r.getInt("start_offset"),r.getInt("end_offset"));}
    /** 小片行转换为纯 Java 契约。 */
    public static ChunkSnapshot chunk(ResultSet r,int n)throws SQLException{return new ChunkSnapshot(r.getString("chunk_id"),r.getString("section_id"),r.getString("parent_id"),r.getInt("index_in_section"),r.getInt("index_in_parent"),r.getInt("start_offset"),r.getInt("end_offset"),r.getString("raw_text"),r.getString("embedding_text"),r.getString("chunk_hash"));}
    /** 保守 Token 上界估计；字节不是精确 Token 用量。 */
    private int bytes(String s){return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
    /** 仅读取固定字符串数组。 */
    private List<String> decode(String s){try{return json.readValue(s,new TypeReference<List<String>>(){});}catch(Exception e){throw new LabException("CONTEXT_MAPPING_INVALID","目录关系损坏");}}
}
