package com.example.ailab.app;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.example.ailab.ai.orchestration.rag.DocumentIngestionPipeline;
import com.example.ailab.ai.orchestration.worker.ReportTaskWorker;
import com.example.ailab.ai.orchestration.planner.PlanValidator;
import com.example.ailab.ai.aggregator.ResultAggregator;
import com.example.ailab.ai.model.ModelGateway;
import com.example.ailab.business.application.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.*;
import com.example.ailab.data.search.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/** S03 真实依赖专项，仅自己创建的随机资料；关闭扫描，不领取用户队列，凭证不进入证据。 */
public final class IngestionNativeValidation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final Map<String,Object> META=new LinkedHashMap<>();
    private static final List<Long> FIXTURE_USERS=new ArrayList<>(),DOCUMENTS=new ArrayList<>();
    private static final Map<String,List<Long>> EXISTING_IDS=new LinkedHashMap<>();
    private static final String PASSWORD="S03-"+UUID.randomUUID()+"-password";
    private static final String SUFFIX=UUID.randomUUID().toString().replace("-","").substring(0,12);
    private static final String TEST_SCHEMA="novid_s03_"+SUFFIX,TEST_INDEX="novid_s03_"+SUFFIX;
    private static Map<String,Object> defaults;
    private static String configuredUrl,testedUrl,origin,ownerToken,userToken,adminToken;
    private static boolean schemaCreated,indexCreated;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RestClientTransport esTransport;
    private static ElasticsearchClient es;
    private static UserContext owner;
    private static long baseId,shortId,longId;
    /** 单项失败保留真实原因类别，不能吞异常后记通过。 */
    @FunctionalInterface private interface Checked { /** 执行确定检查。 */ void run() throws Exception; }

    /** 真实资源只用于合成资料；所有清理按确定 ID，日志只保存错误码和测试名。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        defaults=new LinkedHashMap<>(LocalEnvironmentLoader.read(Path.of(args.length==0?".env":args[0])));
        configuredUrl=configuration("DB_URL");
        META.put("date","2026-10-04 Asia/Shanghai");META.put("worker_scans",0);
        META.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256","not supplied"));
        META.put("fault_scope","real SQL / real ES / configured embedding of synthetic data; ES wrapper faults; explicit fixture leases; no OS kill");
        try {
            require("real".equals(configuration("MODEL_MODE")),"real configured embedding required");
            selectDatabase();startApplication();initializeIndex();createAccounts();
            baseId=context.getBean(KnowledgeBaseApplicationService.class).create(owner,"S03 synthetic "+SUFFIX,"").id();
            check("real_batch_two_partial_bulk_then_context_restart",()->recoverReal(false));
            check("real_batch_two_response_lost_reconciles_without_embedding",()->recoverReal(true));
            check("http_owner_only_ingestion_no_vectors_or_credentials",()->{
                var response=request("GET","/documents/"+shortId+"/ingestion",ownerToken,null,null);var data=expect(200,response);
                require(response.headers().firstValue("cache-control").orElse("").equals("no-store"),"cacheable ingestion");
                require(data.path("progress").path("modelAttempts").asInt()>=2 && data.path("progress").path("peakVectorItems").asInt()<=32,"persistent counters absent");
                require(!data.toString().contains("vectorJson")&&!data.toString().contains("workerId")&&!data.toString().contains("inputHash"),"internal fields leaked");
                expect(403,request("GET","/documents/"+shortId+"/ingestion",userToken,null,null));
                expect(403,request("GET","/documents/"+shortId+"/ingestion",adminToken,null,null));
                expect(200,request("GET","/documents/"+shortId,adminToken,null,null));
                expect(200,request("GET","/documents/"+shortId+"/sections",adminToken,null,null));
                expect(200,request("GET","/documents/"+shortId+"/chunks",adminToken,null,null));
            });
            check("sql_unknown_sending_survives_restart_and_recover_refused",()->unknownSending());
            check("sql_late_model_usage_recorded_without_vector_or_activation",()->lateUsage());
            check("sql_plan_idempotency_conflict_and_unverified_activation",()->planFacts());
            check("sql_stale_fencing_lease_revision_library_deleted_refused",()->staleMatrix());
            check("sql_budget_attempt_input_and_deadline_cannot_reset",()->budgetMatrix());
            check("http_retry_reprocess_create_new_revision_recover_keeps_budget",()->actions());
            check("real_es_final_extra_id_refuses_ready_and_fixed_prune_boundary",()->extraAndCleanup());
        } catch(Throwable error){RESULTS.add(Map.of("case","setup_or_execution","status","FAIL","reason",safeReason(error)));}
        finally {
            closeApplication();cleanupFixtures();
            long passed=RESULTS.stream().filter(r->r.get("status").equals("PASS")).count();META.put("passed",passed);META.put("failed",RESULTS.size()-passed);
            Files.createDirectories(Path.of("var/stage-S03"));Files.writeString(Path.of("var/stage-S03/native-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",META,"cases",RESULTS)));
            System.out.println(JSON.writeValueAsString(META));
        }
        if(RESULTS.stream().anyMatch(r->r.get("status").equals("FAIL")))System.exit(1);
    }

    /** 确定ID的短事务构造并锁定租约，不调用会领取用户队列的claim。 */
    private static IngestionLease fixture(String text) {
        long[] ids=new long[2];
        new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
            var sql=context.getBean(SqlSupport.class);sql.actor(owner,true);
            ids[0]=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,?,'txt')",baseId,owner.userId(),"S03 synthetic");DOCUMENTS.add(ids[0]);
            jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,1,?,?)",ids[0],text,SqlSupport.hash(text));
            ids[1]=sql.insert("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,attempt,worker_id,fencing_token,lease_until,execution_deadline) VALUES(?,1,1,?,'PROCESSING',1,'s03-explicit',1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 600 SECOND))",ids[0],owner.userId());
        });
        return new IngestionLease(ids[1],ids[0],1,1,owner,"s03-explicit",1,"S03 synthetic","txt",text);
    }

    /** 已保存向量先经历真实ES部分写入或完整写入后丢响应，再重启正式上下文恢复。 */
    private static void recoverReal(boolean lost) throws Exception {
        var lease=fixture(("S03 synthetic facts for bounded recovery validation. "+"x".repeat(80)+"\n").repeat(120));
        shortId=lease.documentId();var real=context.getBean(KnowledgeIndexPort.class);
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        KnowledgeIndexPort faulty=new KnowledgeIndexPort() {
            /** 初始化仍受正式实现控制。 */ public void initialize(){real.initialize();}
            /** 第二批真实写入全部或一部分后模拟响应故障，故障不来自真实提供方。 */
            public void index(List<IndexedChunk> chunks){int n=writes.incrementAndGet();if(n==2){real.index(lost?chunks:chunks.subList(0,1));throw new LabException("SEARCH_UNAVAILABLE","S03 injected response loss");}real.index(chunks);}
            /** 检查仍走真实搜索路径。 */ public void verify(List<IndexedChunk> chunks){real.verify(chunks);}
            /** 部分成功判断仍来自真实ES。 */ public Set<String> present(List<IndexedChunk> chunks){return real.present(chunks);}
            /** 全集核验保留真实实现。 */ public void verifyGeneration(long id,int v,long r,int count){real.verifyGeneration(id,v,r,count);}
            /** 清理只沿正式固定边界。 */ public boolean cleanup(IndexCleanupLease l){return real.cleanup(l);}
        };
        var pipeline=new DocumentIngestionPipeline(context.getBean(DocumentIngestionStorePort.class),context.getBean(KnowledgeCapabilityPort.class),context.getBean(com.example.ailab.ai.orchestration.rag.StructureParser.class),context.getBean(ModelGateway.class),faulty);
        denied("SEARCH_UNAVAILABLE",()->pipeline.execute(lease));var store=context.getBean(DocumentIngestionStorePort.class);store.fail(lease,"SEARCH_UNAVAILABLE");
        require(jdbc.queryForObject("SELECT state FROM ingestion_batches WHERE ingestion_id=? AND ordinal=0",String.class,lease.ingestionId()).equals("INDEXED"),"first batch not persisted");
        require(jdbc.queryForObject("SELECT status FROM document_ingestions WHERE id=?",String.class,lease.ingestionId()).equals("FAILED"),"failure hidden");
        int before=jdbc.queryForObject("SELECT model_attempts FROM document_ingestions WHERE id=?",Integer.class,lease.ingestionId());
        long reserved=jdbc.queryForObject("SELECT reserved_input_tokens FROM document_ingestions WHERE id=?",Long.class,lease.ingestionId());
        String until=jdbc.queryForObject("SELECT CAST(execution_deadline AS CHAR) FROM document_ingestions WHERE id=?",String.class,lease.ingestionId());
        int planned=jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_batches WHERE ingestion_id=?",Integer.class,lease.ingestionId());
        require(planned==2 && before==2,"fixture must have exactly two batches");
        closeApplication();startApplication();
        // 配置库回退时恢复与指定夹具领取一同提交，不暴露可被其他实例扫描的FAILED队列窗口。
        new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
            context.getBean(DocumentIngestionStorePort.class).recover(owner,shortId,1);
            jdbc.update("UPDATE document_ingestions SET status='PROCESSING',attempt=attempt+1,fencing_token=2,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",lease.ingestionId());
        });
        require(jdbc.queryForObject("SELECT model_attempts FROM document_ingestions WHERE id=?",Integer.class,lease.ingestionId())==before,"recover reset attempts");
        var resumed=new IngestionLease(lease.ingestionId(),lease.documentId(),1,1,owner,lease.workerId(),2,lease.title(),lease.format(),lease.text());
        var recoveryItems=new java.util.concurrent.atomic.AtomicInteger();
        var restoredIndex=context.getBean(KnowledgeIndexPort.class);
        KnowledgeIndexPort measured=new KnowledgeIndexPort() {
            /** 初始化沿用真实ES实现。 */ public void initialize(){restoredIndex.initialize();}
            /** 观测恢复写入数量，仍使用真实ES。 */ public void index(List<IndexedChunk> chunks){recoveryItems.addAndGet(chunks.size());restoredIndex.index(chunks);}
            /** 搜索可见性和逐项事实均由真实ES确认。 */ public void verify(List<IndexedChunk> chunks){restoredIndex.verify(chunks);}
            /** 恢复只补真实搜索中缺失的项。 */ public Set<String> present(List<IndexedChunk> chunks){return restoredIndex.present(chunks);}
            /** 全集数量也保留正式实现。 */ public void verifyGeneration(long id,int v,long r,int count){restoredIndex.verifyGeneration(id,v,r,count);}
            /** 清理不扩大固定边界。 */ public boolean cleanup(IndexCleanupLease l){return restoredIndex.cleanup(l);}
        };
        new DocumentIngestionPipeline(context.getBean(DocumentIngestionStorePort.class),context.getBean(KnowledgeCapabilityPort.class),context.getBean(com.example.ailab.ai.orchestration.rag.StructureParser.class),context.getBean(ModelGateway.class),measured).execute(resumed);
        int secondSize=jdbc.queryForObject("SELECT item_count FROM ingestion_batches WHERE ingestion_id=? AND ordinal=1",Integer.class,lease.ingestionId());
        require(recoveryItems.get()==(lost?0:secondSize-1),"resume rewrote completed index items");
        META.put(lost?"lost_response_recovery_bulk_items":"partial_bulk_recovery_items",recoveryItems.get());
        require(content(shortId).document().ingestionStatus().equals("READY"),"resume not READY");
        require(jdbc.queryForObject("SELECT model_attempts FROM document_ingestions WHERE id=?",Integer.class,lease.ingestionId())==before,"resume repurchased embedding");
        require(jdbc.queryForObject("SELECT reserved_input_tokens FROM document_ingestions WHERE id=?",Long.class,lease.ingestionId())==reserved,"resume reset token reservation");
        require(until.equals(jdbc.queryForObject("SELECT CAST(execution_deadline AS CHAR) FROM document_ingestions WHERE id=?",String.class,lease.ingestionId())),"resume extended deadline");
        denied("STALE_EXECUTION",()->context.getBean(DocumentIngestionStorePort.class).activate(lease,1));
        META.put(lost?"lost_response_attempts":"partial_bulk_attempts",before);
        META.put(lost?"lost_response_reserved_tokens":"partial_bulk_reserved_tokens",reserved);
        META.put("vector_dimensions",2048);
        META.put("provider_actual_tokens_known",jdbc.queryForObject("SELECT actual_input_tokens FROM document_ingestions WHERE id=?",Long.class,lease.ingestionId()));
    }

    /** 无模型请求的发送窗口SQL注入；重启后正式流水线必须拒绝盲重购。 */
    private static void unknownSending() throws Exception {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);store.beginEmbedding(l,0);
        closeApplication();startApplication();
        denied("EMBEDDING_RESULT_UNKNOWN",()->context.getBean(DocumentIngestionPipeline.class).execute(l));
        store=context.getBean(DocumentIngestionStorePort.class);store.fail(l,"MODEL_TIMEOUT");
        require(jdbc.queryForObject("SELECT state FROM ingestion_batches WHERE ingestion_id=?",String.class,l.ingestionId()).equals("UNKNOWN"),"unknown state lost");
        expect(409,request("POST","/documents/"+l.documentId()+"/index-actions?action=recover&processingRevision=1",ownerToken,null,null));
        require(jdbc.queryForObject("SELECT model_attempts FROM document_ingestions WHERE id=?",Integer.class,l.ingestionId())==1,"unknown attempts reset");
    }

    /** 迟到响应仅结算原发送意图用量，向量仍未知且旧执行者不能提交。 */
    private static void lateUsage() {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);store.beginEmbedding(l,0);
        jdbc.update("UPDATE document_ingestions SET fencing_token=2 WHERE id=?",l.ingestionId());
        store.completeEmbedding(l,0,List.of(List.of(0.5f,0.5f)),"synthetic-late",17);
        require(jdbc.queryForObject("SELECT result FROM ingestion_model_attempts WHERE ingestion_id=?",String.class,l.ingestionId()).equals("LATE_SUCCEEDED"),"late usage lost");
        require(jdbc.queryForObject("SELECT actual_input_tokens FROM document_ingestions WHERE id=?",Long.class,l.ingestionId())==17,"late usage undercounted");
        require(jdbc.queryForObject("SELECT vector_json IS NULL FROM ingestion_batches WHERE ingestion_id=?",Boolean.class,l.ingestionId()),"late vector stored");
        denied("STALE_EXECUTION",()->store.activate(l,1));store.fail(l,"INGESTION_FAILED");
        require(jdbc.queryForObject("SELECT status FROM document_ingestions WHERE id=?",String.class,l.ingestionId()).equals("PROCESSING"),"old failure overwrote new fencing");
    }

    /** 相同计划幂等，输入变化拒绝，向量和索引事实缺失不能激活。 */
    private static void planFacts() {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);var plan=store.batch(l,0).plan();
        store.plan(l,List.of(plan));require(jdbc.queryForObject("SELECT COUNT(*) FROM ingestion_batches WHERE ingestion_id=?",Integer.class,l.ingestionId())==1,"duplicate batch");
        denied("INGESTION_PLAN_CONFLICT",()->store.plan(l,List.of(new IngestionBatchPlan(0,0,plan.count(),plan.batchKey(),SqlSupport.hash("changed"),plan.inputTokens()))));
        denied("INDEX_NOT_READY",()->store.activate(l,plan.count()));
        var parsed=context.getBean(com.example.ailab.ai.orchestration.rag.StructureParser.class).parse(l);
        denied("INGESTION_PLAN_CONFLICT",()->store.saveStructure(l,new ParsedDocument(parsed.sections(),parsed.parents(),parsed.chunks(),SqlSupport.hash("changed config"))));
    }

    /** 小夹具只解析和登记稳定计划，不调用付费模型。 */
    private static IngestionLease prepared() {
        var l=fixture("S03 tiny synthetic budget fixture");var parsed=context.getBean(com.example.ailab.ai.orchestration.rag.StructureParser.class).parse(l);
        var store=context.getBean(DocumentIngestionStorePort.class);store.saveStructure(l,parsed);
        int tokens=parsed.chunks().stream().mapToInt(c->TextWindow.count(c.embeddingText())).sum();
        var summary=new StringBuilder(parsed.configHash());for(var c:parsed.chunks())summary.append('|').append(c.chunkId()).append(':').append(SqlSupport.hash(c.embeddingText()));
        String input=SqlSupport.hash(summary.toString());
        store.plan(l,List.of(new IngestionBatchPlan(0,0,parsed.chunks().size(),SqlSupport.hash(l.ingestionId()+":0:"+input),input,tokens)));return l;
    }

    /** 固定SQL注入分别定位旧执行者、租约、内容版本、新代次与库删除。 */
    private static void staleMatrix() throws Exception {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);
        jdbc.update("UPDATE document_ingestions SET fencing_token=2 WHERE id=?",l.ingestionId());denied("STALE_EXECUTION",()->store.beginEmbedding(l,0));
        jdbc.update("UPDATE document_ingestions SET fencing_token=1,lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",l.ingestionId());require(!store.renew(l),"expired lease renewed");
        jdbc.update("UPDATE document_ingestions SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",l.ingestionId());
        jdbc.update("UPDATE documents SET current_version=2 WHERE id=?",l.documentId());denied("STALE_EXECUTION",()->store.phase(l,"INDEXING"));jdbc.update("UPDATE documents SET current_version=1 WHERE id=?",l.documentId());
        jdbc.update("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,attempt,retryable) VALUES(?,1,2,?,'FAILED',3,FALSE)",l.documentId(),owner.userId());denied("STALE_EXECUTION",()->store.phase(l,"INDEXING"));
        var deleted=prepared();jdbc.update("UPDATE knowledge_bases SET deleted=TRUE WHERE id=?",baseId);
        try{
            denied("STALE_EXECUTION",()->store.beginEmbedding(deleted,0));store.fail(deleted,"STALE_EXECUTION");
            require(jdbc.queryForObject("SELECT error_code FROM document_ingestions WHERE id=?",String.class,deleted.ingestionId()).equals("LIBRARY_DELETED"),"library failure not located");
        }finally{jdbc.update("UPDATE knowledge_bases SET deleted=FALSE WHERE id=?",baseId);}
        var changed=prepared();jdbc.update("UPDATE documents SET current_version=2 WHERE id=?",changed.documentId());
        store.fail(changed,"STALE_EXECUTION");
        require(jdbc.queryForObject("SELECT error_code FROM document_ingestions WHERE id=?",String.class,changed.ingestionId()).equals("DOCUMENT_VERSION_CHANGED"),"version failure not located");
        jdbc.update("UPDATE documents SET current_version=1 WHERE id=?",changed.documentId());
    }

    /** 数据端拒绝耗尽attempt、输入和期限；每个子项由真实SQL事务断言。 */
    private static void budgetMatrix() throws Exception {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);
        jdbc.update("UPDATE document_ingestions SET model_attempts=160 WHERE id=?",l.ingestionId());denied("INGESTION_BUDGET_EXCEEDED",()->store.beginEmbedding(l,0));
        jdbc.update("UPDATE document_ingestions SET model_attempts=0,reserved_input_tokens=2500000 WHERE id=?",l.ingestionId());denied("INGESTION_BUDGET_EXCEEDED",()->store.beginEmbedding(l,0));
        jdbc.update("UPDATE document_ingestions SET execution_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",l.ingestionId());require(!store.renew(l),"deadline extended");
        expect(409,request("POST","/documents/"+l.documentId()+"/index-actions?action=recover&processingRevision=1",ownerToken,null,null));
    }

    /** 新代次接口的SQL夹具在外层事务将新队列关闭，避免其他正式实例领取。 */
    private static void actions() throws Exception {
        var l=prepared();var store=context.getBean(DocumentIngestionStorePort.class);
        expect(400,request("POST","/documents/"+l.documentId()+"/index-actions?action=recover",ownerToken,null,null));
        expect(403,request("POST","/documents/"+l.documentId()+"/index-actions?action=recover&processingRevision=1",adminToken,null,null));
        new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
            store.reprocess(owner,l.documentId());store.reprocess(owner,l.documentId());
            jdbc.update("UPDATE document_ingestions SET attempt=3,status='FAILED',retryable=FALSE WHERE document_id=?",l.documentId());
        });
        require(jdbc.queryForObject("SELECT MAX(processing_revision) FROM document_ingestions WHERE document_id=?",Long.class,l.documentId())==3,"reprocess did not create new revision");
        require(jdbc.queryForObject("SELECT current_version FROM documents WHERE id=?",Integer.class,l.documentId())==1,"reprocess changed content");
    }

    /** 真实ES额外项让全集验证失败；旧代次清理保留当前与未来项。 */
    private static void extraAndCleanup() throws Exception {
        long id=shortId;
        String chunk=jdbc.queryForObject("SELECT chunk_id FROM chunks WHERE document_id=? ORDER BY chunk_id LIMIT 1",String.class,id);
        var source=es.get(g->g.index(TEST_INDEX).id(chunk),Map.class).source();
        es.index(i->i.index(TEST_INDEX).id(chunk+"extra").document(source));es.indices().refresh(r->r.index(TEST_INDEX));
        int expected=jdbc.queryForObject("SELECT expected_chunk_count FROM document_ingestions WHERE document_id=?",Integer.class,id);
        denied("INDEX_NOT_READY",()->context.getBean(KnowledgeIndexPort.class).verifyGeneration(id,1,1,expected));
        es.delete(d->d.index(TEST_INDEX).id(chunk+"extra"));
        var old=new HashMap<String,Object>(source);old.put("processingRevision",0);var future=new HashMap<String,Object>(source);future.put("processingRevision",2);
        es.index(i->i.index(TEST_INDEX).id("s03-old").document(old));es.index(i->i.index(TEST_INDEX).id("s03-future").document(future));es.indices().refresh(r->r.index(TEST_INDEX));
        require(context.getBean(KnowledgeIndexPort.class).cleanup(new IndexCleanupLease(1,"PRUNE_DOCUMENT",id,1,1,1,"fixture",1)),"prune incomplete");
        require(!es.exists(e->e.index(TEST_INDEX).id("s03-old")).value()&&es.exists(e->e.index(TEST_INDEX).id("s03-future")).value()&&es.exists(e->e.index(TEST_INDEX).id(chunk)).value(),"prune boundary crossed");
    }

    /** 明确本人范围的正式原文读取，不能以管理员内部身份获取来源。 */
    private static DocumentContent content(long id){return context.getBean(KnowledgeCapabilityPort.class).document(owner,ScopeRequest.self(),id);}
    /** 校验稳定错误码，不将预期拒绝误写成正常执行。 */
    private static void denied(String code,Runnable action){try{action.run();throw new AssertionError("expected denial "+code);}catch(LabException e){require(e.code().equals(code),"unexpected denial "+e.code());}}
    /** 凭证只读取变量，缺失时仅输出变量名称。 */
    private static String configuration(String key) {
        String value=System.getProperty(key,System.getenv(key));if(value==null)value=Objects.toString(defaults.get(key),null);
        if(value==null)throw new IllegalStateException("missing configuration "+key);return value;
    }

    /** 新随机 schema 优先；1044 仅切换为精确 ID 清理模式，绝不清空已配置库。 */
    private static void selectDatabase() throws Exception {
        try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) {
            META.put("mysql_version", connection.getMetaData().getDatabaseProductVersion());
            try {
                statement.executeUpdate("CREATE DATABASE `" + TEST_SCHEMA + "` CHARACTER SET utf8mb4");
                schemaCreated = true;
            } catch (SQLException error) {
                if (error.getErrorCode() != 1044) throw error;
            }
        }
        URI address = URI.create(configuredUrl.substring(5));
        testedUrl = schemaCreated ? "jdbc:mysql://" + address.getRawAuthority() + "/" + TEST_SCHEMA + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery()) : configuredUrl;
        META.put("isolation", schemaCreated ? "new random database and ES index" : "configured database; exact new fixture user IDs; random ES index");
        if (!schemaCreated) {
            // 只保留主键作最终存在性核验，不读取原有私人正文，也不将这些 ID 写证据。
            try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) {
                for (String table : List.of("users", "knowledge_bases", "documents", "ai_tasks", "profile_memories")) {
                    var ids = new ArrayList<Long>();
                    try (ResultSet rows = statement.executeQuery("SELECT id FROM " + table)) { while (rows.next()) ids.add(rows.getLong(1)); }
                    EXISTING_IDS.put(table, List.copyOf(ids));
                }
            }
        }
    }


    /** 临时正式应用只绑定随机本机端口；三类自动扫描均关闭，不停止用户进程。 */
    private static void startApplication(String... extraArguments) {
        SpringApplication application = new SpringApplication(LabApplication.class);
        application.setDefaultProperties(defaults);
        var arguments = new ArrayList<>(List.of("--spring.datasource.url=" + testedUrl, "--server.port=0", "--server.address=127.0.0.1",
                "--lab.bootstrap.enabled=false", "--lab.search.enabled=true", "--lab.search.index=" + TEST_INDEX,
                "--lab.ingestion.worker-enabled=false", "--lab.task.worker-enabled=false", "--lab.model.models.primary.output-limit=512",
                "--lab.model.models.backup.enabled=false",
                "--logging.level.root=OFF", "--spring.main.banner-mode=off"));
        arguments.addAll(List.of(extraArguments));
        context = application.run(arguments.toArray(String[]::new));
        origin = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
    }


    /** 只建立本次随机索引；正式原索引保留且不进行维护队列消费。 */
    private static void initializeIndex() throws Exception {
        SearchProperties config = context.getBean(SearchProperties.class);
        esTransport = new RestClientTransport(ElasticsearchClientFactory.create(config), new JacksonJsonpMapper());
        es = new ElasticsearchClient(esTransport);
        META.put("es_version", es.info().version().number());
        require(!es.indices().exists(r -> r.index(TEST_INDEX)).value(), "random test index already exists");
        context.getBean(KnowledgeIndexPort.class).initialize();
        indexCreated = true;
        check("real_mysql_v1_v7_and_formal_http_boot", () -> {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='7' AND success=TRUE", Integer.class) == 1, "V7 migration not applied");
            require(request("GET", "/documents/1/ingestion", null, null, null).statusCode() == 401, "anonymous session access allowed");
        });
    }


    /** 构造唯一本次账户，不调用空库 bootstrap，不读取或修改已有管理员。 */
    private static void createAccounts() throws Exception {
        SqlSupport sql = context.getBean(SqlSupport.class);
        String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
        for (String role : List.of("USER", "USER", "ADMIN", "ADMIN")) {
            String name = "s03_" + SUFFIX + "_" + FIXTURE_USERS.size();
            long id = sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,?,FALSE)", name, hash, role);
            FIXTURE_USERS.add(id);
        }
        ownerToken = login(0);
        userToken = login(1);
        adminToken = login(2);
        owner = context.getBean(AccountApplicationService.class).authenticate(ownerToken);
    }


    /** 真实认证必须经正式网络入口，令牌只保存在变量且不写证据。 */
    private static String login(int index) throws Exception {
        return expect(200, request("POST", "/auth/login", null, Map.of("username", "s03_" + SUFFIX + "_" + index, "password", PASSWORD), null)).path("token").asText();
    }


    /** 正式 HTTP 请求不打印认证头；有界超时避免探针无限等待。 */
    private static HttpResponse<String> request(String method,String path,String token,Object body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+"/api/v1"+path)).timeout(Duration.ofSeconds(75));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json; charset=UTF-8");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body),StandardCharsets.UTF_8));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    /** 状态不符只输出固定错误码，不泄露数据库或模型响应内容。 */
    private static JsonNode expect(int status,HttpResponse<String> response) throws Exception {
        require(response.statusCode()==status,"expected HTTP "+status+"; actual HTTP "+response.statusCode()+"; code="+code(response));
        return response.body().isBlank()?JSON.nullNode():JSON.readTree(response.body());
    }


    /** 响应正文可能包含用户资料，只从固定错误 DTO 提取短错误码。 */
    private static String code(HttpResponse<String> response) { try { return JSON.readTree(response.body()).path("code").asText("none"); } catch (Exception ignored) { return "non-JSON"; } }


    /** JDBC 设置有界连接时间，连接参数与凭证仅存内存。 */
    private static Connection connect(String url) throws SQLException {
        Properties properties = new Properties(); properties.setProperty("user", configuration("DB_USERNAME")); properties.setProperty("password", configuration("DB_PASSWORD"));
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "15000");
        return DriverManager.getConnection(url, properties);
    }


    /** 关闭本探针自己的上下文，不接触 IDEA 或其他服务进程。 */
    private static void closeApplication() { if (context != null) { try { context.close(); } catch (Exception ignored) {} context = null; } }


    /** 自己创建的 schema 才可 DROP；回退库只按本次确切用户 ID 删除，保留其余用户数据及迁移。 */
    private static void cleanupFixtures() {
        try {
            if (indexCreated && es != null) { es.indices().delete(r -> r.index(TEST_INDEX)); META.put("temporary_index_removed", true); }
        } catch (Throwable error) {
            RESULTS.add(Map.of("case", "fixture_index_cleanup", "status", "FAIL", "reason", safeReason(error)));
            META.put("cleanup_incomplete", true);
        } finally {
            if (esTransport != null) try { esTransport.close(); } catch (Exception ignored) {}
        }
        // 搜索服务清理失败也继续清理自己的数据库资料，避免一处远程故障阻止独立收尾。
        try {
            if (schemaCreated) {
                require(TEST_SCHEMA.matches("novid_s03_[a-f0-9]{12}") && !URI.create(configuredUrl.substring(5)).getPath().equals("/" + TEST_SCHEMA), "unsafe schema cleanup");
                try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) { statement.executeUpdate("DROP DATABASE `" + TEST_SCHEMA + "`"); }
                META.put("temporary_schema_removed", true);
            } else if (!FIXTURE_USERS.isEmpty()) {
                DriverManagerDataSource source = new DriverManagerDataSource(testedUrl, configuration("DB_USERNAME"), configuration("DB_PASSWORD"));
                JdbcTemplate cleanup = new JdbcTemplate(source); NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(cleanup);
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                    Map<String, Object> users = Map.of("users", FIXTURE_USERS);
                    named.update("DELETE FROM messages WHERE session_id IN (SELECT id FROM sessions WHERE user_id IN (:users))", users);
                    named.update("DELETE FROM sessions WHERE user_id IN (:users)", users);
                    for (String table : List.of("ai_runs", "knowledge_access_audit", "request_deduplications")) named.update("DELETE FROM " + table + " WHERE actor_user_id IN (:users)", users);
                    for (String table : List.of("auth_tokens", "profile_memories")) named.update("DELETE FROM " + table + " WHERE user_id IN (:users)", users);
                    for (String table : List.of("task_document_pages","task_document_coverage","task_step_progress","task_steps","artifacts"))
                        named.update("DELETE FROM " + table + " WHERE task_id IN (SELECT id FROM ai_tasks WHERE requester_user_id IN (:users))",users);
                    named.update("DELETE FROM ai_tasks WHERE requester_user_id IN (:users)",users);
                    for (long id : DOCUMENTS) {
                        Map<String,Object> docs=Map.of("id",id);
                        for(String table:List.of("ingestion_model_attempts","ingestion_batches")) named.update("DELETE FROM "+table+" WHERE ingestion_id IN (SELECT id FROM document_ingestions WHERE document_id=:id)",docs);
                        for (String table : List.of("source_dependencies","chunks","context_parents","document_sections","document_ingestions","document_versions")) named.update("DELETE FROM " + table + " WHERE document_id=:id",docs);
                        named.update("DELETE FROM outbox_events WHERE event_type IN ('INGEST_DOCUMENT','DELETE_DOCUMENT','PRUNE_DOCUMENT') AND resource_id=:id",docs);
                    }
                    named.update("DELETE FROM documents WHERE owner_user_id IN (:users)", users);
                    named.update("DELETE FROM outbox_events WHERE event_type='DELETE_BASE' AND resource_id=:id", Map.of("id", baseId));
                    named.update("DELETE FROM knowledge_bases WHERE owner_user_id IN (:users)", users);
                    named.update("DELETE FROM users WHERE id IN (:users)", users);
                });
                require(named.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (:users)", Map.of("users", FIXTURE_USERS), Integer.class) == 0, "synthetic users retained");
                META.put("exact_fixture_ids_removed", true);
                for (var existing : EXISTING_IDS.entrySet()) {
                    if (existing.getValue().isEmpty()) continue;
                    require(named.queryForObject("SELECT COUNT(*) FROM " + existing.getKey() + " WHERE id IN (:ids)", Map.of("ids", existing.getValue()), Integer.class) == existing.getValue().size(), "existing row disappeared during validation");
                }
                META.put("existing_users_documents_tasks_preferences_retained", true);
            }
        } catch (Throwable error) { RESULTS.add(Map.of("case", "fixture_database_cleanup", "status", "FAIL", "reason", safeReason(error))); META.put("cleanup_incomplete", true); }
    }


    /** 每项结果只输出稳定名字与通过／失败，不回显资料、地址和凭证。 */
    private static void check(String name, Checked action) {
        try { action.run(); RESULTS.add(Map.of("case", name, "status", "PASS")); System.out.println(name+" PASS"); }
        catch (Throwable error) { RESULTS.add(Map.of("case", name, "status", "FAIL", "reason", safeReason(error)));System.out.println(name+" FAIL "+safeReason(error)); }
    }


    /** 固定程序断言文本可写证据；供应商或 JDBC 异常仅写类型与错误码。 */
    private static String safeReason(Throwable error) {
        if (error instanceof AssertionError) return Objects.toString(error.getMessage(), "assertion failed");
        if (error instanceof com.example.ailab.contract.error.LabException lab) return "LabException:" + lab.code();
        if (error instanceof SQLException sql) return "SQLException:" + sql.getErrorCode();
        var chain = new ArrayList<String>();
        for (Throwable cause = error; cause != null && chain.size() < 8; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName());
            // 仅白名单中的项目自有固定注册错误可打印；其余异常消息可能带地址或密钥，仍拒绝输出。
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                    && (cause.getMessage().startsWith("真实模型缺名称、地址或环境凭证")
                    || cause.getMessage().equals("模型窗口或超时配置不合法")
                    || cause.getMessage().equals("两个别名不能作为两个实际主备目标"))) return String.join(" -> ", chain) + ":" + cause.getMessage();
        }
        return String.join(" -> ", chain);
    }


    /** 失败立即停止当前用例，最终非零退出，不把跳过或有异常的检查计通过。 */
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
