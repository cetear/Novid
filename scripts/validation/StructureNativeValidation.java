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

/** S02 真实依赖专项，仅自己创建的随机资料；关闭扫描，不领取用户队列，凭证不进入证据。 */
public final class StructureNativeValidation {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<Map<String,Object>> RESULTS=new ArrayList<>();
    private static final Map<String,Object> META=new LinkedHashMap<>();
    private static final List<Long> FIXTURE_USERS=new ArrayList<>(),DOCUMENTS=new ArrayList<>();
    private static final Map<String,List<Long>> EXISTING_IDS=new LinkedHashMap<>();
    private static final String PASSWORD="S02-"+UUID.randomUUID()+"-password";
    private static final String SUFFIX=UUID.randomUUID().toString().replace("-","").substring(0,12);
    private static final String TEST_SCHEMA="novid_s02_"+SUFFIX,TEST_INDEX="novid_s02_"+SUFFIX;
    private static Map<String,Object> defaults;
    private static String configuredUrl,testedUrl,origin,ownerToken,userToken,adminToken;
    private static boolean schemaCreated,indexCreated;
    private static boolean focused;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RestClientTransport esTransport;
    private static ElasticsearchClient es;
    private static UserContext owner;
    private static long baseId,shortId,longId;
    private static TaskLease resumeLease;
    private static TaskPageCheckpoint firstPage;
    /** 单项失败保留真实原因类别，不能吞异常后记通过。 */
    @FunctionalInterface private interface Checked { /** 执行确定检查。 */ void run() throws Exception; }

    /** 真实资源只用于合成资料；所有清理按确定 ID，日志只保存错误码和测试名。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        defaults=new LinkedHashMap<>(LocalEnvironmentLoader.read(Path.of(args.length==0?".env":args[0])));
        configuredUrl=configuration("DB_URL");
        focused=args.length>1&&args[1].equals("--focused");
        META.put("date","2026-10-04 Asia/Shanghai");META.put("worker_scans",0);
        META.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256","not supplied"));
        META.put("task_and_ingestion_lease_fixture",true);META.put("model_scope",focused?"real embedding of synthetic data; SQL and HTTP supplement; no real chat calls":"real configured single chat target and embedding; synthetic input; max output 512");
        try {
            require("real".equals(configuration("MODEL_MODE")),"real configured target required");
            selectDatabase();startApplication();initializeIndex();createAccounts();
            baseId=context.getBean(KnowledgeBaseApplicationService.class).create(owner,"S02 synthetic "+SUFFIX,"").id();
            shortId=ingest("# 备份规则\n\n晨星项目每周三备份一次，保留七份。规则末尾暗号为星桥。\n","short.md");
            longId=ingest(Files.readString(Path.of("lab-ai/src/test/resources/s02-structure.md")),"long.md");
            check("http_full_root_pagination_reconstructs_long_original",()->pagination(longId));
            check("http_repeat_titles_keep_distinct_ids",()->{
                JsonNode sections=expect(200,request("GET","/documents/"+longId+"/sections?size=100",ownerToken,null,null));
                var duplicate=new ArrayList<String>();for(var s:sections)if(s.path("headingPath").asText().endsWith(" / 同名"))duplicate.add(s.path("sectionId").asText());
                require(duplicate.size()==2&&!duplicate.get(0).equals(duplicate.get(1)),"duplicate title IDs merged");
            });
            check("http_new_chunk_maps_and_estimates_reconstruct_source",()->chunkMaps(longId));
            check("http_ingestion_metadata_v2_no_internal_fields",()->{
                var response=request("GET","/documents/"+longId+"/ingestion",ownerToken,null,null);var data=expect(200,response);
                require(data.path("mappingVersion").asText().equals("utf16-lines-blocks-v2")&&data.path("countSource").asText().equals(TextWindow.COUNT_SOURCE),"metadata not real mapping v2");
                require(response.headers().firstValue("cache-control").orElse("").equals("no-store")&&!data.has("workerId")&&!data.has("leaseUntil")&&!data.has("storageKey"),"private metadata leaked");
            });
            check("http_user_isolation_and_admin_cross_library_read",()->{
                expect(403,request("GET",detail(longId,0),userToken,null,null));expect(403,request("GET","/documents/"+longId+"/ingestion",userToken,null,null));
                expect(200,request("GET",detail(longId,0),adminToken,null,null));expect(403,request("GET","/documents/"+longId+"/ingestion",adminToken,null,null));
            });
            check("http_stale_revision_and_bad_unicode_cursor_refused",()->{
                String path=detail(longId,0).replace("processingRevision=1","processingRevision=99");require(code(request("GET",path,ownerToken,null,null)).equals("CONTEXT_VERSION_CONFLICT"),"old revision not rejected");
                String text=content(longId).text();int split=text.indexOf("😀")+1;expect(400,request("GET",detail(longId,split),ownerToken,null,null));
                expect(400,request("GET",detail(longId,0).replace("maxTokens=300","maxTokens=4001"),ownerToken,null,null));
            });
            check("sql_old_candidate_revision_produces_no_evidence",()->{
                var chunk=chunks(longId).get(0);require(context.getBean(DocumentContextRepository.class).expand(scope(),List.of(new ChunkCandidate(longId,1,99,chunk.chunkId(),1)),4000).isEmpty(),"old revision expanded");
            });
            check("sql_forged_parent_relation_refused",()->{
                var seed=chunks(longId).get(0);var foreign=chunks(shortId).get(0);
                jdbc.update("UPDATE chunks SET parent_id=? WHERE chunk_id=?",foreign.contextParentId(),seed.chunkId());
                try{denied("CONTEXT_MAPPING_INVALID",()->context.getBean(DocumentContextRepository.class).expand(scope(),List.of(new ChunkCandidate(longId,1,1,seed.chunkId(),1)),4000));}
                finally{jdbc.update("UPDATE chunks SET parent_id=? WHERE chunk_id=?",seed.contextParentId(),seed.chunkId());}
            });
            check("sql_reduced_parent_and_seed_limits_change_expansion",()->expansionParameters());
            check("sql_false_neighbor_ordinal_refused",()->{
                var list=chunks(longId);var text=content(longId).text();var directory=context.getBean(DocumentContextPort.class).sections(scope(),longId,0,500);
                // 选中确实进入整父段分支的夹具；超限父段会合法退回种子邻域，域外损坏行不属于交付证据。
                var seed=list.stream().filter(c->{var group=list.stream().filter(n->n.contextParentId().equals(c.contextParentId())).toList();
                    int start=group.stream().mapToInt(ChunkSnapshot::startOffset).min().orElseThrow(),end=group.stream().mapToInt(ChunkSnapshot::endOffset).max().orElseThrow();
                    String heading=directory.stream().filter(s->s.sectionId().equals(c.sectionId())).findFirst().orElseThrow().headingPath();
                    return group.size()>1&&TextWindow.count(text.substring(start,end))+TextWindow.count(heading)+64<=2000;}).findFirst().orElseThrow();
                var neighbor=list.stream().filter(c->c.contextParentId().equals(seed.contextParentId())&&!c.chunkId().equals(seed.chunkId())).findFirst().orElseThrow();
                jdbc.update("UPDATE chunks SET index_in_section=? WHERE chunk_id=?",neighbor.chunkIndexInSection()+5000,neighbor.chunkId());
                try{denied("CONTEXT_MAPPING_INVALID",()->context.getBean(DocumentContextRepository.class).expand(scope(),List.of(new ChunkCandidate(longId,1,1,seed.chunkId(),1)),4000));}
                finally{jdbc.update("UPDATE chunks SET index_in_section=? WHERE chunk_id=?",neighbor.chunkIndexInSection(),neighbor.chunkId());}
            });
            check("sql_corrupt_source_map_refused",()->{
                var seed=chunks(longId).get(0);String original=jdbc.queryForObject("SELECT source_map FROM chunks WHERE chunk_id=?",String.class,seed.chunkId());
                var maps=(com.fasterxml.jackson.databind.node.ArrayNode)JSON.readTree(original);((com.fasterxml.jackson.databind.node.ObjectNode)maps.get(0)).put("sourceStartOffset",maps.get(0).path("sourceStartOffset").asInt()+1);
                jdbc.update("UPDATE chunks SET source_map=? WHERE chunk_id=?",JSON.writeValueAsString(maps),seed.chunkId());
                try{denied("CONTEXT_MAPPING_INVALID",()->context.getBean(DocumentContextRepository.class).expand(scope(),List.of(new ChunkCandidate(longId,1,1,seed.chunkId(),1)),4000));}
                finally{jdbc.update("UPDATE chunks SET source_map=? WHERE chunk_id=?",original,seed.chunkId());}
            });
            check("sql_table_header_delivered_as_separate_exact_bundle",()->{
                var seed=chunks(longId).stream().filter(c->c.sourceMap().stream().anyMatch(TextMapping::repeatedHeader)).reduce((a,b)->b).orElseThrow();
                var bundles=context.getBean(DocumentContextRepository.class).expand(scope(),List.of(new ChunkCandidate(longId,1,1,seed.chunkId(),1)),4000);
                require(bundles.stream().anyMatch(e->e.matchedChunkIds().isEmpty()&&e.text().contains("| 项目 | 单位 |")),"header context absent");
                for(var bundle:bundles)require(content(longId).text().substring(bundle.startOffset(),bundle.endOffset()).equals(bundle.text()),"header bundle fake source offset");
                require(bundles.stream().mapToInt(e->TextWindow.count(e.headingPath())+TextWindow.count(e.text())+64).sum()<=4000,"header evidence exceeded hard bound");
            });
            check("sql_page_checkpoint_idempotency_cas_and_pause_fencing",()->pageCheckpoint());
            check("sql_restart_page_position_and_budget_persist",()->{
                long taskId=resumeLease.task().taskId();closeApplication();startApplication();
                var pages=context.getBean(TaskStorePort.class).pages(resumeLease);require(pages.size()==1&&pages.get(0).equals(firstPage),"successful page lost on context restart");
                require(context.getBean(TaskStorePort.class).remainingModelTurns(resumeLease)==5,"persistent turn budget reset");
                require(expect(200,request("GET","/tasks/"+taskId,ownerToken,null,null)).path("coverage").get(0).path("readEndOffset").asInt()==firstPage.page().endOffset(),"HTTP coverage lost");
                // 仅本次合成持久化探针延长租约，不让另一正式实例因真实模型验收较慢而领取它。
                jdbc.update("UPDATE ai_tasks SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 1200 SECOND) WHERE id=?",taskId);
            });
            if(!focused){
                check("real_model_short_report_full_coverage_and_citation",()->report(shortId,false));
                check("real_model_long_report_partial_exact_remaining_ranges",()->report(longId,true));
            }
            check("sql_new_revision_old_page_and_history_location_refused",()->{
                jdbc.update("UPDATE document_versions SET active_processing_revision=2 WHERE document_id=? AND document_version=1",longId);
                try{expect(409,request("GET",detail(longId,0),ownerToken,null,null));denied("CONTEXT_VERSION_CONFLICT",()->context.getBean(TaskStorePort.class).pages(resumeLease));}
                finally{jdbc.update("UPDATE document_versions SET active_processing_revision=1 WHERE document_id=? AND document_version=1",longId);}
            });
            check(focused?"http_revoked_source_denies_page_and_metadata_supplement":"http_revoked_source_denies_page_metadata_and_artifact",()->{
                jdbc.update("UPDATE knowledge_bases SET enabled=FALSE WHERE id=?",baseId);
                try{expect(403,request("GET",detail(longId,0),ownerToken,null,null));expect(403,request("GET","/documents/"+longId+"/ingestion",adminToken,null,null));denied("ACCESS_DENIED",()->context.getBean(TaskStorePort.class).pages(resumeLease));
                    if(!focused){var artifact=jdbc.queryForObject("SELECT MAX(id) FROM artifacts WHERE requester_user_id=?",Long.class,owner.userId());expect(403,request("GET","/artifacts/"+artifact,ownerToken,null,null));}}
                finally{jdbc.update("UPDATE knowledge_bases SET enabled=TRUE WHERE id=?",baseId);}
            });
            check("http_admin_downgrade_revokes_old_token_and_cross_library_page",()->{
                var admin=context.getBean(AccountApplicationService.class).authenticate(login(3));
                context.getBean(AccountApplicationService.class).update(admin,FIXTURE_USERS.get(2),true,UserContext.Role.USER);
                expect(401,request("GET",detail(longId,0),adminToken,null,null));String downgraded=login(2);
                expect(403,request("GET",detail(longId,0),downgraded,null,null));expect(403,request("GET","/documents/"+longId+"/ingestion",downgraded,null,null));
            });
            check("http_failed_latest_intent_keeps_old_active_revision",()->{
                new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
                    ValidationSql.from(context).actor(owner,true);
                    jdbc.update("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,attempt,error_code) VALUES(?,1,2,?,'FAILED',3,'DOCUMENT_PARSE_FAILED')",longId,owner.userId());
                });
                var data=expect(200,request("GET","/documents/"+longId+"/ingestion",ownerToken,null,null));
                require(data.path("processingRevision").asLong()==2&&data.path("activeProcessingRevision").asLong()==1&&data.path("status").asText().equals("FAILED")&&data.path("errorCode").asText().equals("DOCUMENT_PARSE_FAILED"),"latest failure confused with active success");
                expect(200,request("GET",detail(longId,0),ownerToken,null,null));
            });
            check("http_revised_content_cannot_highlight_old_ranges",()->{
                // 修订和关闭本次新意图在一个短事务，不留下可被用户正式实例扫描领取的窗口。
                new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
                    context.getBean(DocumentApplicationService.class).revise(owner,shortId,1,"新版","完全不同的新正文😀");
                    jdbc.update("UPDATE document_ingestions SET status='FAILED',attempt=3 WHERE document_id=? AND document_version=2",shortId);
                });
                expect(409,request("GET",detail(shortId,0),ownerToken,null,null));
            });
            check("http_deleted_source_refuses_pages_and_stored_checkpoint",()->{
                expect(200,request("DELETE","/documents/"+longId+"?documentVersion=1",ownerToken,null,null));
                expect(403,request("GET",detail(longId,0),ownerToken,null,null));expect(403,request("GET","/documents/"+longId+"/ingestion",ownerToken,null,null));
                denied("ACCESS_DENIED",()->context.getBean(TaskStorePort.class).pages(resumeLease));
            });
        } catch(Throwable error){RESULTS.add(Map.of("case","setup_or_execution","status","FAIL","reason",safeReason(error)));}
        finally {
            closeApplication();cleanupFixtures();
            long passed=RESULTS.stream().filter(r->r.get("status").equals("PASS")).count();META.put("passed",passed);META.put("failed",RESULTS.size()-passed);
            Files.createDirectories(Path.of("var/stage-S02"));Files.writeString(Path.of(focused?"var/stage-S02/native-focused-results.json":"var/stage-S02/native-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",META,"cases",RESULTS)));
            System.out.println(JSON.writeValueAsString(META));
        }
        if(RESULTS.stream().anyMatch(r->r.get("status").equals("FAIL")))System.exit(1);
    }

    /** 创建可控 PROCESSING 批次并显式执行自己的入库，不用扫描领取生产队列。 */
    private static long ingest(String text,String title) {
        long[] values=new long[2];var transaction=new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.executeWithoutResult(status->{var sql=ValidationSql.from(context);sql.actor(owner,true);
            values[0]=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,?,'md')",baseId,owner.userId(),title);DOCUMENTS.add(values[0]);
            jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,1,?,?)",values[0],text,SqlSupport.hash(text));
            values[1]=sql.insert("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,attempt,worker_id,fencing_token,lease_until) VALUES(?,1,1,?,'PROCESSING',1,'s02-explicit',1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND))",values[0],owner.userId());
        });
        context.getBean(DocumentIngestionPipeline.class).execute(new IngestionLease(values[1],values[0],1,1,owner,"s02-explicit",1,title,"md",text));
        check("real_embedding_es_ingest_"+title,()->require(content(values[0]).document().ingestionStatus().equals("READY"),"ingestion not READY"));return values[0];
    }
    /** 全文根页按绝对游标重建，使用正式 HTTP 与真实 SQL，无模型参与。 */
    private static void pagination(long id) throws Exception {
        String original=content(id).text();var assembled=new StringBuilder();int cursor=0,pages=0;
        while(true){var response=request("GET",detail(id,cursor),ownerToken,null,null);var page=JSON.treeToValue(expect(200,response),SectionPage.class);
            require(page.startOffset()==cursor&&page.endOffset()>cursor&&TextWindow.count(page.text())<=300,"page cursor or bound invalid");assembled.append(page.text());pages++;
            if(page.complete()){require(page.nextOffset()==null&&page.remainingStartOffset()==page.remainingEndOffset(),"completed page has remainder");break;}cursor=page.nextOffset();require(pages<300,"pagination did not terminate");
        }
        require(original.equals(assembled.toString()),"pagination source coverage lost");META.put("long_document_http_pages",pages);META.put("long_document_utf16_length",original.length());
    }
    /** 块映射、重复表头和短尾实际存入 MySQL，不只验证内存解析对象。 */
    private static void chunkMaps(long id) {
        String text=content(id).text();boolean repeated=false,code=false;boolean[] covered=new boolean[text.length()];
        for(var c:chunks(id)){require(c.rawText().equals(text.substring(c.startOffset(),c.endOffset()))&&c.tokenCount()==TextWindow.count(c.embeddingText())&&c.tokenCount()<=500,"chunk raw or bound mismatch");
            for(int i=c.startOffset();i<c.endOffset();i++)covered[i]=true;
            for(var m:c.sourceMap()){require(text.substring(m.sourceStartOffset(),m.sourceEndOffset()).equals(c.embeddingText().substring(m.embeddingStartOffset(),m.embeddingEndOffset())),"persisted mapping wrong");repeated|=m.repeatedHeader();code|=m.blockType().equals("CODE");}}
        for(int i=0;i<text.length();i++)if(!Character.isWhitespace(text.charAt(i)))require(covered[i],"source unit not covered");require(repeated&&code,"block or repeated header absent");
    }
    /** 同一真实 SQL 数据集用不同窄参数，核对父段／种子上限确实影响扩展。 */
    private static void expansionParameters() {
        var sql=ValidationSql.from(context);var docs=context.getBean(DocumentSqlRepository.class);var all=chunks(longId);
        var candidate=all.stream().filter(c->c.rawText().contains("中文与表情")).findFirst().orElseThrow();
        var hits=List.of(new ChunkCandidate(longId,1,1,candidate.chunkId(),1),new ChunkCandidate(shortId,1,1,chunks(shortId).get(0).chunkId(),0.5));
        var expanded=context.getBean(DocumentContextRepository.class).expand(scope(),hits,4000);
        var reduced=new DocumentContextRepository(sql.support(),docs,new ContextPolicy(3,1,1,100,0,1000)).expand(scope(),hits,1000);
        require(expanded.size()==2&&reduced.size()==1,"seed/final parameter not effective");require(expanded.get(0).text().length()>reduced.get(0).text().length(),"parent limit not effective");
        require(reduced.stream().mapToInt(e->TextWindow.count(e.text())+TextWindow.count(e.headingPath())+64).sum()<=1000,"reduced evidence exceeded bound");
    }
    /** 合成任务在创建短事务内模拟执行权，不对配置库进行真实 claim；页提交使用正式仓库 CAS。 */
    private static TaskLease task(long documentId) {
        TaskLease[] result=new TaskLease[1];new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class)).executeWithoutResult(status->{
            var request=new TaskRequest("RESEARCH_REPORT","用最短中文列出备份规则或正文要点并保留版本引用，总结不超过150字。",ScopeRequest.self(),List.of(documentId),"s02-task-"+UUID.randomUUID());
            var snapshot=context.getBean(TaskApplicationService.class).create(owner,request);jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s02-explicit',fencing_token=fencing_token+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),claimed_at=CURRENT_TIMESTAMP(6),started_at=CURRENT_TIMESTAMP(6),heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=?",snapshot.taskId());
            long fence=jdbc.queryForObject("SELECT fencing_token FROM ai_tasks WHERE id=?",Long.class,snapshot.taskId());result[0]=new TaskLease(context.getBean(TaskStorePort.class).read(owner,snapshot.taskId()),request,owner,"s02-explicit",fence);
        });return result[0];
    }
    /** 人工种入的摘要明确是 SQL 合成夹具；不当作真实模型成功。 */
    private static void pageCheckpoint() {
        var tasks=context.getBean(TaskStorePort.class);resumeLease=task(longId);var d=content(longId).document();var root=context.getBean(DocumentContextPort.class).sections(scope(),longId,0,1).get(0);
        tasks.initializeCoverage(resumeLease,List.of(new DocumentCoverage(longId,1,1,root.sectionId(),0,0,0,0,content(longId).text().length(),false,TextWindow.COUNT_SOURCE)));
        var page=context.getBean(DocumentContextPort.class).documentPage(scope(),longId,1,1,0,300);
        firstPage=new TaskPageCheckpoint(0,page,"合成页摘要 [D"+longId+"v1]",List.of(new SourceDependency(baseId,longId,1)));tasks.reserveModelTurn(resumeLease);tasks.checkpointPage(resumeLease,firstPage);tasks.checkpointPage(resumeLease,firstPage);
        require(tasks.pages(resumeLease).size()==1,"same page duplicated");denied("OPERATION_CONFLICT",()->tasks.checkpointPage(resumeLease,new TaskPageCheckpoint(0,page,"不同合成摘要",firstPage.sourceDependencies())));
        check("sql_noncontinuous_page_index_cas_refused",()->{
            var next=context.getBean(DocumentContextPort.class).documentPage(scope(),longId,1,1,page.endOffset(),300);
            denied("CONTEXT_VERSION_CONFLICT",()->tasks.checkpointPage(resumeLease,new TaskPageCheckpoint(2,next,"合成后页摘要 [D"+longId+"v1]",firstPage.sourceDependencies())));
        });
        tasks.action(owner,resumeLease.task().taskId(),"pause");denied("STALE_EXECUTION",()->tasks.checkpointPage(resumeLease,firstPage));tasks.action(owner,resumeLease.task().taskId(),"resume");
        // 恢复仍只针对这一个已知合成任务，保持旧页和预算。
        jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s02-explicit',lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),claimed_at=CURRENT_TIMESTAMP(6) WHERE id=?",resumeLease.task().taskId());
        long fence=jdbc.queryForObject("SELECT fencing_token FROM ai_tasks WHERE id=?",Long.class,resumeLease.task().taskId());resumeLease=new TaskLease(tasks.read(owner,resumeLease.task().taskId()),resumeLease.request(),owner,"s02-explicit",fence);
    }
    /** 正式 Worker、真实单目标 SDK、SQL 页检查点与私人产物；只显式执行本任务，不运行 scan。 */
    private static void report(long documentId,boolean partial) throws Exception {
        var lease=task(documentId);var worker=new ReportTaskWorker(context.getBean(TaskStorePort.class),context.getBean(KnowledgeCapabilityPort.class),context.getBean(ModelGateway.class),context.getBean(PlanValidator.class),context.getBean(ResultAggregator.class),context.getBean(DocumentContextPort.class),context.getBean(com.example.ailab.ai.orchestration.rag.RagProperties.class));
        try{var run=ReportTaskWorker.class.getDeclaredMethod("run",TaskLease.class);run.setAccessible(true);run.invoke(worker,lease);}finally{worker.close();}
        var result=context.getBean(TaskStorePort.class).read(owner,lease.task().taskId());require(result.status().equals(partial?"PARTIAL":"SUCCEEDED"),"report status="+result.status()+" error="+result.errorCode());
        var coverage=result.coverage().get(0);require(coverage.complete()!=partial&&coverage.readEndOffset()==coverage.remainingStartOffset()&&coverage.remainingEndOffset()==content(documentId).text().length(),"coverage not exact");
        require(result.modelAttempts()<=10&&jdbc.queryForObject("SELECT model_turns FROM ai_tasks WHERE id=?",Integer.class,result.taskId())<=6,"report persistent budget exceeded");
        var response=request("GET","/artifacts/"+result.artifactId(),ownerToken,null,null);require(response.statusCode()==200&&response.body().contains("覆盖说明")&&response.body().contains("[D"+documentId+"v1]"),"report download or citation missing");
        expect(403,request("GET","/artifacts/"+result.artifactId(),adminToken,null,null));
        META.put(partial?"real_long_report_pages":"real_short_report_pages",coverage.completedPages());
        META.put(partial?"real_long_report_remaining_utf16":"real_short_report_remaining_utf16",coverage.remainingEndOffset()-coverage.remainingStartOffset());
        META.put(partial?"real_long_report_turns":"real_short_report_turns",jdbc.queryForObject("SELECT model_turns FROM ai_tasks WHERE id=?",Integer.class,result.taskId()));
        Files.writeString(Path.of(partial?"var/stage-S02/real-long-report.md":"var/stage-S02/real-short-report.md"),response.body());
    }
    /** 固定根章节用于全文分页，不把标题名当作 ID。 */
    private static String detail(long id,int after){return "/documents/"+id+"/sections/d"+id+"v1r1s0?documentVersion=1&processingRevision=1&afterOffset="+after+"&maxTokens=300";}
    /** 服务端本人可信范围，每次数据读取还会复核身份。 */
    private static AuthorizedKnowledgeScope scope(){return context.getBean(KnowledgeCapabilityPort.class).authorize(owner,ScopeRequest.self());}
    /** 仅读取本次合成资料，不查询用户正文。 */
    private static DocumentContent content(long id){return context.getBean(KnowledgeCapabilityPort.class).document(owner,ScopeRequest.self(),id);}
    /** 有界分页合成小片，不假设第一页就是全部。 */
    private static List<ChunkSnapshot> chunks(long id){var result=new ArrayList<ChunkSnapshot>();for(int offset=0;offset<5000;offset+=100){var page=context.getBean(DocumentContextPort.class).chunks(scope(),id,offset,100);result.addAll(page);if(page.size()<100)break;}return result;}
    /** 指定错误码必须实际抛出，否则失败；不将跳过当拒绝通过。 */
    private static void denied(String code,Runnable action){try{action.run();throw new AssertionError("expected denial "+code);}catch(LabException e){require(e.code().equals(code),"unexpected denial "+e.code());}}
    /** 显式环境变量优先，避免凭证写入命令行或打印的配置映射。 */
    private static String configuration(String key) {
        String value = System.getenv(key);
        if (value == null) value = Objects.toString(defaults.get(key), null);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing required configuration key");
        return value;
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
        check("real_mysql_v1_v6_and_formal_http_boot", () -> {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='6' AND success=TRUE", Integer.class) == 1, "V6 migration not applied");
            require(request("GET", "/documents/1/ingestion", null, null, null).statusCode() == 401, "anonymous session access allowed");
        });
    }


    /** 构造唯一本次账户，不调用空库 bootstrap，不读取或修改已有管理员。 */
    private static void createAccounts() throws Exception {
        ValidationSql sql = ValidationSql.from(context);
        String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
        for (String role : List.of("USER", "USER", "ADMIN", "ADMIN")) {
            String name = "s02_" + SUFFIX + "_" + FIXTURE_USERS.size();
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
        return expect(200, request("POST", "/auth/login", null, Map.of("username", "s02_" + SUFFIX + "_" + index, "password", PASSWORD), null)).path("token").asText();
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
                require(TEST_SCHEMA.matches("novid_s02_[a-f0-9]{12}") && !URI.create(configuredUrl.substring(5)).getPath().equals("/" + TEST_SCHEMA), "unsafe schema cleanup");
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
                    for (String table : List.of("task_plans","task_document_pages","task_document_coverage","task_step_progress","task_steps","artifacts"))
                        named.update("DELETE FROM " + table + " WHERE task_id IN (SELECT id FROM ai_tasks WHERE requester_user_id IN (:users))",users);
                    named.update("DELETE FROM ai_tasks WHERE requester_user_id IN (:users)",users);
                    for (long id : DOCUMENTS) {
                        Map<String,Object> docs=Map.of("id",id);
                        // S03新增向量和attempt外键，先精确清本次文档的批次事实，不能删除原有用户资料。
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
        try { action.run(); RESULTS.add(Map.of("case", name, "status", "PASS")); }
        catch (Throwable error) { RESULTS.add(Map.of("case", name, "status", "FAIL", "reason", safeReason(error))); }
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
