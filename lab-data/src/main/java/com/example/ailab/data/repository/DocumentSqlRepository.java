package com.example.ailab.data.repository;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentStorePort;
import com.example.ailab.contract.error.LabException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
/** 原文为权威数据，当前查询不会从 ES 借用旧版本。 */
@Repository
public class DocumentSqlRepository implements DocumentStorePort {
    private final SqlSupport sql;
    public static final String JOIN=" FROM documents d JOIN knowledge_bases k ON k.id=d.knowledge_base_id JOIN document_versions v ON v.document_id=d.id AND v.document_version=d.current_version ";
    /** 装配同一数据源。 */
    public DocumentSqlRepository(SqlSupport sql){this.sql=sql;}
    /** actor 行锁序列化同用户去重；创建与去重结果同事务提交。 */
    @Transactional
    public DocumentSnapshot create(UserContext actor,UploadCommand c){
        sql.owner(actor,c.knowledgeBaseId(),true);
        String hash=SqlSupport.hash(c.knowledgeBaseId()+"\n"+c.title().length()+":"+c.title()+"\n"+c.format()+"\n"+c.text());
        var old=sql.jdbc.query("SELECT request_hash,resource_id FROM request_deduplications WHERE actor_user_id=? AND namespace='DOCUMENT_CREATE' AND request_key=?",(r,n)->Map.entry(r.getString(1),r.getLong(2)),actor.userId(),c.idempotencyKey());
        if(!old.isEmpty()){if(!old.get(0).getKey().equals(hash))throw new LabException("OPERATION_CONFLICT","相同去重键的参数不同");return metadata(old.get(0).getValue());}
        if(sql.jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE owner_user_id=? AND deleted=FALSE",Long.class,actor.userId())>=10000)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","文档数量超过用户配额");
        long id=insert(actor,c.knowledgeBaseId(),c.title(),c.format(),c.text(),false);
        sql.jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'DOCUMENT_CREATE',?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY))",actor.userId(),c.idempotencyKey(),hash,id);
        return metadata(id);
    }
    /** data 内部新文档事实，调用者必须已经取得 owner 行锁。 */
    public long insert(UserContext actor,long base,String title,String format,String text,boolean generated){
        long id=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format,`generated`) VALUES(?,?,?,?,?)",base,actor.userId(),title,format,generated);
        sql.jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,1,?,?)",id,text,SqlSupport.hash(text));sql.jdbc.update("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id) VALUES(?,1,1,?)",id,actor.userId());sql.event("INGEST_DOCUMENT",id,1);sql.changed();return id;
    }
    /** 内部无范围读取只供已经授权的事务结果使用。 */
    public DocumentSnapshot metadata(long id){return sql.jdbc.query("SELECT d.*,v.ingestion_status,v.active_processing_revision"+JOIN+"WHERE d.id=?",SqlSupport::document,id).stream().findFirst().orElseThrow(LabException::denied);}
    /** 分页候选额外复核衍生来源，不泄露受限标题。 */
    public List<DocumentSnapshot> list(AuthorizedKnowledgeScope scope,int offset,int limit){
        var p=new MapSqlParameterSource().addValue("limit",limit).addValue("offset",offset);String filter=sql.scope(scope,p);
        var rows=sql.named.query("SELECT d.*,v.ingestion_status,v.active_processing_revision"+JOIN+"WHERE d.deleted=FALSE AND "+filter+" ORDER BY d.id LIMIT :limit OFFSET :offset",p,SqlSupport::document);
        // 受限结果被过滤，因此页内可能少于 limit；不能为了补足无界循环查询。
        return rows.stream().filter(d->allowedSources(scope,d.id(),d.documentVersion())).toList();
    }
    /** 查询源文与来源递归复核，失败不返回正文。 */
    public DocumentContent read(AuthorizedKnowledgeScope scope,long id){
        var p=new MapSqlParameterSource().addValue("id",id);String filter=sql.scope(scope,p);
        var docs=sql.named.query("SELECT d.*,v.ingestion_status,v.active_processing_revision"+JOIN+"WHERE d.id=:id AND d.deleted=FALSE AND "+filter,p,SqlSupport::document);
        if(docs.isEmpty())throw LabException.denied();var doc=docs.get(0);var sources=dependencies(id,doc.documentVersion());verifySources(scope,sources);
        String text=sql.jdbc.queryForObject("SELECT raw_text FROM document_versions WHERE document_id=? AND document_version=?",String.class,id,doc.documentVersion());
        if(scope.actor().role()==UserContext.Role.ADMIN)sql.jdbc.update("INSERT INTO knowledge_access_audit(actor_user_id,action,resource_id) VALUES(?,'READ_DOCUMENT',?)",scope.actor().userId(),id);
        return new DocumentContent(doc,text,sources);
    }
    /** owner 与内容版本 CAS 修订，新版本保留此前服务端来源。 */
    @Transactional
    public DocumentSnapshot revise(UserContext actor,long id,int version,String title,String text){
        sql.actor(actor,true);var doc=metadata(id);sql.owner(actor,doc.knowledgeBaseId(),true);
        if(sql.jdbc.update("UPDATE documents SET title=?,current_version=current_version+1 WHERE id=? AND current_version=? AND deleted=FALSE",title,id,version)!=1)throw new LabException("OPERATION_CONFLICT","文档版本已变化");
        sql.jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,?,?,?)",id,version+1,text,SqlSupport.hash(text));
        sql.jdbc.update("INSERT INTO source_dependencies(document_id,document_version,source_base_id,source_document_id,source_document_version) SELECT document_id,?,source_base_id,source_document_id,source_document_version FROM source_dependencies WHERE document_id=? AND document_version=?",version+1,id,version);
        sql.jdbc.update("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id) VALUES(?,?,1,?)",id,version+1,actor.userId());sql.event("INGEST_DOCUMENT",id,version+1);sql.changed();return metadata(id);
    }
    /** 删除 MySQL 先生效，即使 ES 滞后也不得返回原文。 */
    @Transactional
    public void delete(UserContext actor,long id,int version){sql.actor(actor,true);var doc=metadata(id);sql.owner(actor,doc.knowledgeBaseId(),false);if(sql.jdbc.update("UPDATE documents SET deleted=TRUE WHERE id=? AND current_version=? AND deleted=FALSE",id,version)!=1)throw new LabException("OPERATION_CONFLICT","文档版本已变化");sql.event("DELETE_DOCUMENT",id,version);sql.changed();}
    /** 统计使用与资料读取相同的范围，排除衍生来源当前不可读的文档。 */
    public KnowledgeStatistics statistics(AuthorizedKnowledgeScope scope){
        var p=new MapSqlParameterSource();String filter=sql.scope(scope,p);
        // 所有来源在保存时已扁平化，SQL 按当前用户角色／来源库状态复核。
        String safe=" NOT EXISTS (SELECT 1 FROM source_dependencies s JOIN documents sd ON sd.id=s.source_document_id JOIN knowledge_bases sk ON sk.id=s.source_base_id WHERE s.document_id=d.id AND s.document_version=d.current_version AND (sd.deleted=TRUE OR sk.deleted=TRUE OR sk.enabled=FALSE"+(scope.actor().role()==UserContext.Role.ADMIN?"":" OR sk.owner_user_id<>:actor")+"))";
        return sql.named.queryForObject("SELECT COUNT(*) total,COALESCE(SUM(v.ingestion_status='RECEIVED'),0) received,COALESCE(SUM(v.ingestion_status='READY'),0) ready"+JOIN+"WHERE d.deleted=FALSE AND "+filter+" AND "+safe,p,(r,n)->new KnowledgeStatistics(r.getLong("total"),r.getLong("received"),r.getLong("ready")));
    }
    /** 来源权限独立于当前选择范围，管理员降级后即使输出本人所有也拒绝。 */
    public void verifySources(AuthorizedKnowledgeScope scope,List<SourceDependency> sources){sql.actor(scope.actor(),false);walk(scope.actor(),sources,new HashSet<>(),new HashSet<>(),0);}
    /** 有限递归拒绝环、缺失历史版本与无权来源。 */
    private void walk(UserContext actor,List<SourceDependency> sources,Set<String> path,Set<String> visited,int depth){
        if(depth>8||sources.size()>32)throw new LabException("CONTEXT_MAPPING_INVALID","来源依赖超过限制");
        for(var s:sources){String key=s.documentId()+":"+s.documentVersion();if(path.contains(key))throw new LabException("CONTEXT_MAPPING_INVALID","来源依赖环");if(visited.contains(key))continue;if(visited.size()>=32)throw new LabException("CONTEXT_MAPPING_INVALID","来源过多");
            var rows=sql.jdbc.query("SELECT d.id"+JOIN+"WHERE d.id=? AND d.knowledge_base_id=? AND d.deleted=FALSE AND k.enabled=TRUE AND k.deleted=FALSE AND EXISTS(SELECT 1 FROM document_versions history WHERE history.document_id=d.id AND history.document_version=?)"+(actor.role()==UserContext.Role.ADMIN?"":" AND k.owner_user_id=?"),(r,n)->r.getLong(1),actor.role()==UserContext.Role.ADMIN?new Object[]{s.documentId(),s.knowledgeBaseId(),s.documentVersion()}:new Object[]{s.documentId(),s.knowledgeBaseId(),s.documentVersion(),actor.userId()});
            if(rows.isEmpty())throw LabException.denied();path.add(key);walk(actor,dependencies(s.documentId(),s.documentVersion()),path,visited,depth+1);path.remove(key);visited.add(key);
        }
    }
    /** 获取服务器保存的原始来源，不相信客户端清除标志。 */
    public List<SourceDependency> dependencies(long id,int version){return sql.jdbc.query("SELECT source_base_id,source_document_id,source_document_version FROM source_dependencies WHERE document_id=? AND document_version=? ORDER BY source_document_id,source_document_version",(r,n)->new SourceDependency(r.getLong(1),r.getLong(2),r.getInt(3)),id,version);}
    /** 只过滤授权失败，基础设施故障必须正常上报。 */
    private boolean allowedSources(AuthorizedKnowledgeScope scope,long id,int version){try{verifySources(scope,dependencies(id,version));return true;}catch(LabException e){if(e.code().equals("ACCESS_DENIED"))return false;throw e;}}
}
