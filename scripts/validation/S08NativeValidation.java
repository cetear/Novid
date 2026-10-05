package com.example.ailab.app;

import com.example.ailab.business.application.*;
import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.*;
import com.example.ailab.data.search.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.elasticsearch.client.Request;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** 真MySQL／可选真ES／HTTP治理专项；模型禁用，仅合成资料，所有Worker和自动清理关闭。 */
public final class S08NativeValidation {
    private static final List<String> CHECKS = new ArrayList<>();
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final Instant OLD = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2000-01-02T00:00:00Z");
    private record Fixture(long base, long doc, long ingestion, ChunkSnapshot current, String oldSection) { }

    /** 唯一用户和索引；凭证只由正式加载器读取，finally按明确ID清理自己的测试资料。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        boolean databaseOnly = Arrays.asList(args).contains("--database-only");
        String index = "s08-" + UUID.randomUUID().toString().replace("-", "");
        var app = new SpringApplication(LabApplication.class);
        LocalEnvironmentLoader.initialize(app, Path.of(".env"));
        var options = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1", "--lab.bootstrap.enabled=false",
                "--lab.task.worker-enabled=false", "--lab.ingestion.worker-enabled=false", "--lab.governance.cleanup-enabled=false",
                "--lab.observability.export-enabled=false", "--lab.model.mode=mock", "--spring.main.banner-mode=off", "--logging.level.root=OFF",
                "--lab.search.enabled=" + !databaseOnly, "--lab.search.index=" + index, "--lab.search.dimensions=2", "--lab.search.embedding-model-version=s08-fixture"));
        try (var context = app.run(options.toArray(String[]::new))) {
            var sql = context.getBean(SqlSupport.class); var jdbc = sql.jdbc;
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var originalUsers = jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class);
            check(jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens WHERE expires_at<?", Integer.class, Timestamp.from(CUTOFF)) == 0
                    && jdbc.queryForObject("SELECT COUNT(*) FROM request_deduplications WHERE expires_at<?", Integer.class, Timestamp.from(CUTOFF)) == 0
                    && jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_access_audit WHERE created_at<?", Integer.class, Timestamp.from(CUTOFF)) == 0
                    && jdbc.queryForObject("SELECT COUNT(*) FROM document_ingestions WHERE created_at<?", Integer.class, Timestamp.from(CUTOFF)) == 0,
                    "ancient_cutoff_has_no_existing_user_records");
            var actors = new ArrayList<UserContext>(); var fixtures = new ArrayList<Fixture>(); boolean indexed = false;
            var search = context.getBean(ElasticsearchRepository.class); var governance = context.getBean(GovernanceStorePort.class);
            var policy = context.getBean(KnowledgeAccessPolicy.class);
            try {
                for (String role : List.of("USER", "USER", "ADMIN")) actors.add(tx.execute(s -> actor(sql, role)));
                for (int n=0;n<3;n++) { int position=n; fixtures.add(tx.execute(s -> fixture(sql, actors.get(position==1?1:0),position==2))); }
                var u1=actors.get(0);var u2=actors.get(1);var admin=actors.get(2);var d1=fixtures.get(0);var d2=fixtures.get(1);
                if (!databaseOnly) {
                    // 测试向量是固定合成数值，不调用或冒称真实embedding模型。
                    indexed=true; search.initialize();
                    search.index(List.of(indexed(d1,u1),indexed(d2,u2)));
                    search.verify(List.of(indexed(d1,u1),indexed(d2,u2)));
                    cache(search,policy,sql,tx,u1,u2,admin,d1,d2);
                }
                int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();
                http(context,sql,actors,d1,port);
                retention(governance,sql,tx,u1,admin,fixtures.get(2));
                preference(context,sql,u1);
                check(governance.audits(admin,0,100).stream().filter(a->a.actorUserId()==admin.userId()).allMatch(a->a.resultCount()!=null && a.knowledgeBaseIds()!=null && a.resourceIds()!=null && "DELIVERABLE".equals(a.outcome())),"mysql_new_audits_have_result_and_scope_metadata");
                denied(()->governance.audits(u1,0,100),"mysql_user_admin_audit_denied");
                check(governance.metrics(admin,Instant.now().minusSeconds(3600)).accessEvents()>0,"mysql_admin_aggregate_is_available");
                // 权限变更为真实SQL夹具，不伪装成OS强杀、提供方故障或完整问答模型流程。
                tx.executeWithoutResult(s->{sql.actor(admin,true);jdbc.update("UPDATE users SET role='USER',permission_version=permission_version+1 WHERE id=?",admin.userId());});
                denied(()->governance.audits(admin,0,100),"mysql_stale_admin_after_role_change_denied");
                if (!databaseOnly) denied(()->search.search(scope(admin,ScopeRequest.Mode.ALL),"缓存",List.of(0.6f,0.8f),"s08-fixture"),"cached_admin_all_after_demotion_denied");
                check(context.getBeansOfType(GovernanceMaintenance.class).isEmpty(),"automatic_user_data_cleanup_disabled_in_validation");
            } finally {
                try { if (indexed) {
                    // 只删除本次随机索引，绝不删除／重建正式索引；网络调用不包数据库事务。
                    context.getBean(org.elasticsearch.client.RestClient.class).performRequest(new Request("DELETE", "/"+index));
                    check(true,"own_random_es_index_removed");
                }
                } finally { tx.executeWithoutResult(s -> cleanup(sql,actors,fixtures)); }
                check(jdbc.queryForList("SELECT id FROM users ORDER BY id", Long.class).equals(originalUsers),"original_user_ids_unchanged_own_users_removed");
                for (var f:fixtures) check(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE id=?",Integer.class,f.doc())==0,"own_document_"+fixtures.indexOf(f)+"_removed");
            }
            var evidence=new LinkedHashMap<String,Object>();evidence.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256"));
            evidence.put("passed",CHECKS.size());evidence.put("checks",CHECKS);evidence.put("database_version",jdbc.queryForObject("SELECT VERSION()",String.class));
            evidence.put("es",databaseOnly?"NOT_RUN":"real isolated index with synthetic two-dimensional vectors");
            evidence.put("models","NOT_RUN: zero model or embedding calls");
            evidence.put("migration_version",jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE",Integer.class));
            // 本探针只验证治理边界；后续媒体虽已有实现，不能从治理专项推导它们已验收。
            evidence.put("not_verified",List.of("real database outage (fault injection separately)","full HTTP question plus paid model plus SQL chain","production throughput or multi-instance rate limiting","backup restore RPO/RTO","S04 provider automatic failover and quality","S07 prices and final invoice","media and presentation end-to-end quality are outside this governance probe"));
            Files.createDirectories(Path.of("var/stage-S08"));Files.writeString(Path.of("var/stage-S08/"+(databaseOnly?"database-native-results.json":"native-results.json")),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
            System.out.println(JSON.writeValueAsString(Map.of("passed",CHECKS.size(),"own_fixtures_removed",true,"models","NOT_RUN")));
        }
    }

    /** 当前授权SQL与真实ES缓存命中、Scope切换、版本变化／滞后分别精确断言。 */
    private static void cache(ElasticsearchRepository search,KnowledgeAccessPolicy policy,SqlSupport sql,TransactionTemplate tx,
                              UserContext u1,UserContext u2,UserContext admin,Fixture d1,Fixture d2) {
        var all=policy.authorize(admin,new ScopeRequest(ScopeRequest.Mode.ALL,List.of(),null));
        var first=search.search(all,"缓存",List.of(0.6f,0.8f),"s08-fixture");
        check(first.size()==2 && search.expand(all,first,4000).size()==2,"real_es_admin_all_two_documents_authorized");
        long hits=search.cacheStatistics()[0];var second=search.search(all,"缓存",List.of(0.6f,0.8f),"s08-fixture");
        check(second.equals(first)&&search.cacheStatistics()[0]==hits+1,"real_es_query_candidate_cache_hit");
        check(search.search(policy.authorize(u1,ScopeRequest.self()),"缓存",List.of(0.6f,0.8f),"s08-fixture").stream().allMatch(c->c.documentId()==d1.doc()),"cached_admin_results_not_shared_with_user1");
        check(search.search(policy.authorize(u2,ScopeRequest.self()),"缓存",List.of(0.6f,0.8f),"s08-fixture").stream().allMatch(c->c.documentId()==d2.doc()),"user2_scope_isolated");
        check(search.search(policy.authorize(admin,ScopeRequest.self()),"缓存",List.of(0.6f,0.8f),"s08-fixture").isEmpty(),"admin_all_to_self_does_not_reuse_all");
        check(search.search(policy.authorize(admin,new ScopeRequest(ScopeRequest.Mode.SELECTED,List.of(d1.base()),null)),"缓存",List.of(0.6f,0.8f),"s08-fixture").size()==1,"selected_scope_separate_key");
        tx.executeWithoutResult(s->{sql.owner(u1,d1.base(),true);sql.jdbc.update("UPDATE knowledge_bases SET enabled=FALSE,version=version+1 WHERE id=?",d1.base());sql.changed();});
        check(search.expand(all,first,4000).stream().noneMatch(e->e.document().id()==d1.doc()),"old_cached_candidates_base_disabled_not_delivered");
        long misses=search.cacheStatistics()[1];var staleEs=search.search(all,"缓存",List.of(0.6f,0.8f),"s08-fixture");
        check(search.cacheStatistics()[1]==misses+1 && search.expand(all,staleEs,4000).size()==1,"knowledge_epoch_invalidates_all_cache_es_still_stale");
        tx.executeWithoutResult(s->{sql.actor(u1,true);sql.jdbc.update("UPDATE knowledge_bases SET enabled=TRUE WHERE id=?",d1.base());sql.jdbc.update("UPDATE document_versions SET active_processing_revision=3 WHERE document_id=?",d1.doc());sql.changed();});
        check(search.expand(all,first,4000).stream().noneMatch(e->e.document().id()==d1.doc()),"processing_revision_changed_cached_old_content_rejected");
        tx.executeWithoutResult(s->{sql.actor(u1,true);sql.jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum,ingestion_status,active_processing_revision) VALUES(?,2,'修订后的合成原文',?,'READY',2)",d1.doc(),SqlSupport.hash("修订后的合成原文"));sql.jdbc.update("UPDATE documents SET current_version=2 WHERE id=?",d1.doc());sql.changed();});
        check(search.expand(all,first,4000).stream().noneMatch(e->e.document().id()==d1.doc()),"document_revised_cached_old_version_rejected");
        tx.executeWithoutResult(s->{sql.actor(u2,true);sql.jdbc.update("UPDATE documents SET deleted=TRUE WHERE id=?",d2.doc());sql.changed();});
        check(search.expand(all,first,4000).isEmpty(),"document_deleted_cached_source_not_delivered");
        tx.executeWithoutResult(s->{sql.actor(u1,true);sql.jdbc.update("UPDATE users SET enabled=FALSE,permission_version=permission_version+1 WHERE id=?",u1.userId());});
        denied(()->search.search(scope(u1,ScopeRequest.Mode.SELF),"缓存",List.of(0.6f,0.8f),"s08-fixture"),"cached_query_disabled_user_rejected");
        tx.executeWithoutResult(s->{sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE",Integer.class);sql.jdbc.update("UPDATE users SET enabled=TRUE,permission_version=1 WHERE id=?",u1.userId());});
    }

    /** 真HTTP认证令牌由正式哈希端口签发，审计列表／原文读取及聚合都走正式仓库。 */
    private static void http(org.springframework.context.ApplicationContext context,SqlSupport sql,List<UserContext> actors,Fixture fixture,int port) throws Exception {
        var tokens=context.getBean(AuthTokenStorePort.class);var raw=new ArrayList<String>();
        for(var actor:actors) {String token=UUID.randomUUID().toString()+UUID.randomUUID();raw.add(token);tokens.issue(context.getBean(UserStorePort.class).user(actor.userId()).orElseThrow(),AccountApplicationService.digest(token),Instant.now().plusSeconds(300));}
        check(get(port,"/admin/access-audit",null).statusCode()==401,"real_http_anonymous_admin_audit_401");
        check(get(port,"/admin/access-audit",raw.get(0)).statusCode()==403,"real_http_user_admin_audit_403");
        check(get(port,"/admin/metrics?days=31",raw.get(2)).statusCode()==400,"real_http_metric_window_bounded");
        long auditStart=sql.jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM knowledge_access_audit",Long.class);
        var list=get(port,"/knowledge-bases?scopeMode=ALL&ownerUserId="+actors.get(0).userId(),raw.get(2));check(list.statusCode()==200,"real_http_admin_all_list_audited");
        var docs=get(port,"/documents?scopeMode=ALL&ownerUserId="+actors.get(0).userId(),raw.get(2));check(docs.statusCode()==200,"real_http_admin_document_list_audited");
        check(get(port,"/documents/"+fixture.doc()+"/source",raw.get(2)).statusCode()==200,"real_http_admin_raw_source_audited");
        var audit=get(port,"/admin/access-audit?afterId="+auditStart+"&size=100",raw.get(2));check(audit.statusCode()==200 && audit.headers().firstValue("Cache-Control").orElse("").equals("no-store"),"real_http_admin_audit_no_store");
        var records=JSON.readTree(audit.body());check(records.findValuesAsText("action").containsAll(List.of("LIST_BASES","LIST_DOCUMENTS","READ_DOCUMENT")),"real_http_list_and_source_actions_queryable");
        check(records.findValuesAsText("ownerUserId").contains(Long.toString(actors.get(0).userId())),"real_http_audit_preserves_exact_owner_filter");
        check(records.findValues("resourceIds").stream().anyMatch(ids->ids.toString().contains(Long.toString(fixture.doc()))),"real_http_audit_preserves_deliverable_object_ids");
        check(!audit.body().contains("raw_text")&&!audit.body().contains("content")&&!audit.body().contains("修订后的合成原文"),"real_http_audit_has_no_private_body");
        var metrics=get(port,"/admin/metrics",raw.get(2));check(metrics.statusCode()==200&&!metrics.body().contains("actorUserId")&&!metrics.body().contains("resourceId"),"real_http_metrics_has_only_aggregates");
        check(get(port,"/admin/access-audit?size=101",raw.get(2)).statusCode()==400,"real_http_audit_page_bounded");
        // 查询自己的新审计ID之后验证游标，避免把历史全量返回当作分页成功。
        long latest=sql.jdbc.queryForObject("SELECT MAX(id) FROM knowledge_access_audit WHERE actor_user_id=?",Long.class,actors.get(2).userId());
        check(JSON.readTree(get(port,"/admin/access-audit?afterId="+latest,raw.get(2)).body()).isEmpty(),"real_http_audit_cursor_excludes_previous_rows");
    }

    /** 真MySQL最小截止范围，引用保留／每类上限／可靠事实和失败回滚分别断言。 */
    private static void retention(GovernanceStorePort store,SqlSupport sql,TransactionTemplate tx,UserContext actor,UserContext admin,Fixture fixture) {
        var jdbc=sql.jdbc;var source=JSON.createArrayNode().add(JSON.createObjectNode().put("knowledgeBaseId",fixture.base()).put("documentId",fixture.doc()).put("documentVersion",1)).toString();
        long task=tx.execute(s->sql.insert("INSERT INTO ai_tasks(requester_user_id,task_type,request_json,request_hash,status,model_attempts,model_turns) VALUES(?,'FAQ','{}',?,'PAUSED',3,2)",actor.userId(),"0".repeat(64)));
        for(String table:List.of("messages","sessions","approvals","task_steps","artifacts","task_document_pages","source_dependencies","task_document_coverage","ai_tasks")) {
            tx.executeWithoutResult(status->{try {
                reference(sql,actor,fixture,task,source,table);
                check(store.purge(CUTOFF,CUTOFF,100).structureRows()==0,"mysql_reference_retained_"+table);
            } finally {status.setRollbackOnly();}});
        }
        var fee=new FeeRepository(sql);var feeScope=new FeeScope(actor,"RUN",UUID.randomUUID().toString(),UUID.randomUUID().toString());
        tx.executeWithoutResult(s->{var reserved=fee.reserve(feeScope,UUID.randomUUID().toString(),"synthetic","CHAT",10,0,null,"CNY",BigDecimal.TEN,10000,false);fee.sending(reserved);fee.complete(reserved,null,null,"SYNTHETIC_UNKNOWN");});
        tx.executeWithoutResult(s->{
            for(int n=0;n<3;n++) {jdbc.update("INSERT INTO auth_tokens(token_hash,user_id,permission_version,expires_at) VALUES(?,?,1,?)",SqlSupport.hash(UUID.randomUUID().toString()),actor.userId(),Timestamp.from(OLD));
                jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'DOCUMENT_CREATE',?,?,?,?)",actor.userId(),"old"+n,"0".repeat(64),fixture.doc(),Timestamp.from(OLD));
                jdbc.update("INSERT INTO knowledge_access_audit(actor_user_id,action,created_at) VALUES(?,'S08_FIXTURE',?)",admin.userId(),Timestamp.from(OLD));}
            jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'TASK_CREATE','paused',?,?,?)",actor.userId(),"0".repeat(64),task,Timestamp.from(OLD));
            jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'DOCUMENT_CREATE','seven-days',?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY))",actor.userId(),"0".repeat(64),fixture.doc());
        });
        try {
            tx.executeWithoutResult(status->{store.purge(CUTOFF,CUTOFF,1);throw new IllegalStateException("S08_INJECTED_AFTER_PURGE");});
            throw new AssertionError("injected cleanup failure not propagated");
        } catch(IllegalStateException injected) {
            check("S08_INJECTED_AFTER_PURGE".equals(injected.getMessage()),"mysql_cleanup_injected_failure_propagates");
        }
        check(jdbc.queryForObject("SELECT COUNT(*) FROM auth_tokens WHERE expires_at<?",Integer.class,Timestamp.from(CUTOFF))==3,"mysql_cleanup_injected_failure_atomic_rollback");
        for(int n=0;n<3;n++) {var result=store.purge(CUTOFF,CUTOFF,1);check(result.tokens()==1&&result.deduplications()==1&&result.auditEvents()==1&&result.structureRows()==1,"mysql_cleanup_single_row_page_"+n);}
        check(jdbc.queryForObject("SELECT COUNT(*) FROM request_deduplications WHERE actor_user_id=? AND request_key IN ('paused','seven-days')",Integer.class,actor.userId())==2,"mysql_active_task_and_seven_day_dedup_retained");
        check(jdbc.queryForObject("SELECT model_attempts FROM ai_tasks WHERE id=?",Integer.class,task)==3,"mysql_task_recovery_budget_not_deleted");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE scope_id=? AND state='UNKNOWN'",Integer.class,jdbc.queryForObject("SELECT scope_id FROM fee_scopes WHERE resource_id=?",String.class,feeScope.resourceId()))==1,"mysql_unknown_fee_reservation_preserved");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM document_versions WHERE document_id=?",Integer.class,fixture.doc())==1
                && jdbc.queryForObject("SELECT COUNT(*) FROM document_ingestions WHERE document_id=?",Integer.class,fixture.doc())==2,"mysql_original_versions_and_ingestion_facts_retained");
        check(store.purge(CUTOFF,CUTOFF,1).structureRows()==0,"mysql_cleanup_restart_no_additional_structure_delete");
        try {store.purge(Instant.now(),Instant.now(),101);throw new AssertionError("unbounded cleanup accepted");} catch(LabException expected) {check(expected.code().equals("INVALID_ARGUMENTS"),"mysql_invalid_cleanup_limits_rejected");}
    }

    /** 各类真实来源夹具一次只加一种引用，回滚后不遗留新任务／会话／审批。 */
    private static void reference(SqlSupport sql,UserContext actor,Fixture f,long task,String source,String table) {
        var jdbc=sql.jdbc;
        switch(table) {
            case "source_dependencies" -> jdbc.update("INSERT INTO source_dependencies(document_id,document_version,source_base_id,source_document_id,source_document_version) VALUES(?,1,?,?,1)",f.doc(),f.base(),f.doc());
            case "ai_tasks" -> jdbc.update("UPDATE ai_tasks SET request_json=? WHERE id=?","{\"documentIds\":["+f.doc()+"]}",task);
            case "task_document_coverage" -> jdbc.update("INSERT INTO task_document_coverage(task_id,document_id,document_version,processing_revision,section_id,read_start,read_end,remaining_start,remaining_end,count_source) VALUES(?,?,1,1,?,0,0,0,1,'S08_FIXTURE')",task,f.doc(),f.oldSection());
            case "task_steps" -> jdbc.update("INSERT INTO task_steps(task_id,step_id,content,source_json,partial) VALUES(?,'research','合成',?,FALSE)",task,source);
            case "artifacts" -> jdbc.update("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum) VALUES(?,?,'fixture.md','text/markdown','合成',?,?)",actor.userId(),task,source,"0".repeat(64));
            case "task_document_pages" -> {reference(sql,actor,f,task,source,"task_document_coverage");jdbc.update("INSERT INTO task_document_pages(task_id,document_id,page_index,page_json,summary,source_json) VALUES(?,?,0,'{}','合成',?)",task,f.doc(),source);}
            case "approvals" -> jdbc.update("INSERT INTO approvals(approval_id,operation_id,actor_user_id,knowledge_base_id,target_version,title,content,parameters_hash,source_json,expires_at) VALUES(?,?,?, ?,1,'合成','合成',?,?,CURRENT_TIMESTAMP(6))",UUID.randomUUID().toString(),UUID.randomUUID().toString(),actor.userId(),f.base(),"0".repeat(64),source);
            case "sessions", "messages" -> {
                long session=sql.insert("INSERT INTO sessions(user_id,title,scope_json,summary_content,summary_covered_through_seq,summary_source_json,next_seq) VALUES(?,'合成','{}',?,?,?,2)",actor.userId(),table.equals("sessions")?"合成":null,table.equals("sessions")?1L:null,table.equals("sessions")?source:null);
                if(table.equals("messages")) jdbc.update("INSERT INTO messages(session_id,user_id,seq,role,status,content,source_json,source_reference_json,scope_json) VALUES(?,?,1,'ASSISTANT','SUCCESS','合成',?,'[]','{}')",session,actor.userId(),source);
            }
            default -> throw new IllegalArgumentException("未知合成引用");
        }
    }

    /** 偏好从不缓存，正式删除重置本人摘要并撤销在途窗口。 */
    private static void preference(org.springframework.context.ApplicationContext context,SqlSupport sql,UserContext actor) {
        var memory=context.getBean(MemoryStorePort.class);var saved=memory.create(actor,"S08合成偏好");
        long session=sql.insert("INSERT INTO sessions(user_id,title,scope_json,summary_content,summary_covered_through_seq,summary_source_json,next_seq) VALUES(?,'合成','{}','旧摘要',1,'[]',2)",actor.userId());
        memory.delete(actor,saved.id(),saved.version());
        check(memory.list(actor).isEmpty(),"mysql_deleted_preference_not_reused");
        check(sql.jdbc.queryForObject("SELECT summary_content IS NULL AND context_floor_seq=1 FROM sessions WHERE id=?",Boolean.class,session),"mysql_preference_delete_invalidates_private_session_summary");
    }

    /** 合成账户不含真实密码，正式Bearer签发仅使用其无凭证快照。 */
    private static UserContext actor(SqlSupport sql,String role) {
        long id=sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,'unused-fixture',?,FALSE)","s08_"+UUID.randomUUID().toString().replace("-",""),role);
        return new UserContext(id,UserContext.Role.valueOf(role),true,1,false);
    }

    /** 只创建终态合成处理记录，无Outbox／待领队列，已有正式Worker不会消费测试资料。 */
    private static Fixture fixture(SqlSupport sql,UserContext actor,boolean old) {
        long base=sql.insert("INSERT INTO knowledge_bases(owner_user_id,name) VALUES(?,'S08合成库')",actor.userId());
        long doc=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,'S08合成文档','txt')",base,actor.userId());
        String text="S08缓存权限证据";
        sql.jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum,ingestion_status,active_processing_revision) VALUES(?,1,?,?,'READY',2)",doc,text,SqlSupport.hash(text));
        String oldSection=null;ChunkSnapshot current=null;long oldIngestion=0;
        for(int revision=1;revision<=2;revision++) {
            long ingestion=sql.insert("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,created_at) VALUES(?,1,?,?,'READY',?)",doc,revision,actor.userId(),Timestamp.from(old&&revision==1?OLD:Instant.now()));
            if(revision==1)oldIngestion=ingestion;
            String section="s08:"+doc+":"+revision, parent=section+":p",chunk=section+":c";
            sql.jdbc.update("INSERT INTO document_sections(section_id,document_id,document_version,processing_revision,ancestor_json,heading_path,ordinal,start_offset,end_offset) VALUES(?,?,1,?,'[]','合成',0,0,?)",section,doc,revision,text.length());
            sql.jdbc.update("INSERT INTO context_parents(parent_id,document_id,document_version,processing_revision,section_id,ordinal,start_offset,end_offset) VALUES(?,?,1,?,?,0,0,?)",parent,doc,revision,section,text.length());
            sql.jdbc.update("INSERT INTO chunks(chunk_id,document_id,document_version,processing_revision,section_id,parent_id,index_in_section,index_in_parent,start_offset,end_offset,raw_text,embedding_text,chunk_hash) VALUES(?,?,1,?,?,?,0,0,0,?,?,?,?)",chunk,doc,revision,section,parent,text.length(),text,text,SqlSupport.hash(text));
            if(revision==1)oldSection=section;else current=new ChunkSnapshot(chunk,section,parent,0,0,0,text.length(),text,text,SqlSupport.hash(text));
        }
        sql.changed(); return new Fixture(base,doc,oldIngestion,current,oldSection);
    }

    /** 固定向量只用来验证真实ES范围和版本过滤，不参与模型／费用验收。 */
    private static IndexedChunk indexed(Fixture f,UserContext actor) {return new IndexedChunk(f.base(),actor.userId(),f.doc(),1,2,f.current(),List.of(0.6f,0.8f),"s08-fixture");}
    /** 旧范围仅供负向复核，不能作为新的授权来源。 */
    private static AuthorizedKnowledgeScope scope(UserContext actor,ScopeRequest.Mode mode) {return new AuthorizedKnowledgeScope(actor,mode,List.of(),null,Instant.now());}
    /** HTTP凭证仅在请求头和内存中，不保存响应中的原文或登录凭证到证据。 */
    private static HttpResponse<String> get(int port,String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(10));
        if(token!=null)request.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    /** 只清自己的明确主键；不按前缀模糊删除现有资料，不关闭约束。 */
    private static void cleanup(SqlSupport sql,List<UserContext> actors,List<Fixture> fixtures) {
        var jdbc=sql.jdbc; jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE",Integer.class);
        for(var actor:actors) {
            jdbc.update("DELETE a FROM fee_attempts a JOIN fee_scopes f ON f.scope_id=a.scope_id WHERE f.actor_user_id=?",actor.userId());
            jdbc.update("DELETE FROM fee_scopes WHERE actor_user_id=?",actor.userId());
            jdbc.update("DELETE FROM knowledge_access_audit WHERE actor_user_id=?",actor.userId());
            jdbc.update("DELETE FROM request_deduplications WHERE actor_user_id=?",actor.userId());
            jdbc.update("DELETE FROM profile_memories WHERE user_id=?",actor.userId());
            jdbc.update("DELETE FROM messages WHERE user_id=?",actor.userId());jdbc.update("DELETE FROM sessions WHERE user_id=?",actor.userId());
            jdbc.update("DELETE FROM ai_tasks WHERE requester_user_id=?",actor.userId());
            jdbc.update("DELETE FROM auth_tokens WHERE user_id=?",actor.userId());
        }
        for(var f:fixtures) {
            jdbc.update("DELETE FROM chunks WHERE document_id=?",f.doc());jdbc.update("DELETE FROM context_parents WHERE document_id=?",f.doc());jdbc.update("DELETE FROM document_sections WHERE document_id=?",f.doc());
            jdbc.update("DELETE FROM document_ingestions WHERE document_id=?",f.doc());jdbc.update("DELETE FROM document_versions WHERE document_id=?",f.doc());jdbc.update("DELETE FROM documents WHERE id=?",f.doc());jdbc.update("DELETE FROM knowledge_bases WHERE id=?",f.base());
        }
        for(var actor:actors)jdbc.update("DELETE FROM users WHERE id=?",actor.userId());
    }
    /** 每个检查名称单独保存，失败即时停止，不补造后续通过。 */
    private static void check(boolean passed,String name) {if(!passed)throw new AssertionError(name);CHECKS.add(name);}
    /** 权限失败必须为稳定拒绝码，不能把基础设施失败算作拦截通过。 */
    private static void denied(Runnable action,String name) {try{action.run();throw new AssertionError(name);}catch(LabException failure){check(failure.code().equals("ACCESS_DENIED"),name);}}
}
