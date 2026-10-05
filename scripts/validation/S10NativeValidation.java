package com.example.ailab.app;

import com.example.ailab.business.application.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.SqlSupport;
import com.example.ailab.ai.orchestration.media.PresentationExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.io.*;
import java.net.*;
import java.net.http.*;

/** S10真SQL／真实本地PPTX／真HTTP分层验证，所有素材均合成，不调用提供方或正式队列。 */
public final class S10NativeValidation {
    private static final List<String> CHECKS=new ArrayList<>();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    // S11复用夹具时文件全部落到独立证据根目录，不与S10历史恢复状态互相覆盖。
    private static final Path EVIDENCE=Path.of(System.getProperty("validation.evidence-root","var/stage-S10")).toAbsolutePath().normalize();
    private static final String CONFIG="a".repeat(64);
    private record Fixture(UserContext actor,TaskRequest request,long id,long base,long doc) { }
    private record RestartState(Fixture fixture,long processId,long artifactId,String operationHash,String feeHash,String deadline,int modelAttempts,String usersBefore,String tasksBefore,String jarHash) { }
    /** 正式包执行迁移校验，隔离文件目录，回滚故障矩阵，精确清理HTTP提交夹具。 */
    public static void main(String[] args)throws Exception{
        // 输入仅允许工作区var下的专用目录，拒绝把真实媒体根目录当测试文件空间。
        if(!EVIDENCE.startsWith(Path.of("var").toAbsolutePath().normalize())||EVIDENCE.equals(Path.of("var").toAbsolutePath().normalize())||EVIDENCE.startsWith(Path.of("var/media").toAbsolutePath().normalize()))throw new IllegalArgumentException("证据目录越界");
        Files.createDirectories(EVIDENCE);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));var app=new SpringApplication(LabApplication.class);LocalEnvironmentLoader.initialize(app,Path.of(".env"));
        try(var context=app.run("--server.port=0","--server.address=127.0.0.1","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false","--lab.media.worker-enabled=false",
                "--lab.ingestion.worker-enabled=false","--lab.governance.cleanup-enabled=false","--lab.observability.export-enabled=false","--lab.model.mode=mock","--lab.search.enabled=false",
                "--lab.media.storage-root="+EVIDENCE.resolve("files"),"--spring.main.banner-mode=off","--logging.level.root=OFF")){
            var sql=ValidationSql.from(context);var tasks=context.getBean(TaskStorePort.class);var media=context.getBean(MediaStorePort.class);var files=context.getBean(MediaFilePort.class);var artifacts=context.getBean(ArtifactStorePort.class);
            var exporter=context.getBean(PresentationPort.class);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            // 两个独立JVM通过真实SQL和文件恢复；不领取任何正式队列，也不运行Provider。
            if(args.length==1&&args[0].startsWith("restart-")){restart(args[0],sql,tasks,media,files,exporter,tx);return;}
            var users=sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class);var taskIds=sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class);
            var upstream=sql.jdbc.queryForList("SELECT id,status,model_attempts,model_turns FROM ai_tasks WHERE id IN (78,85) ORDER BY id");
            for(int scenario=0;scenario<10;scenario++){
                final int test=scenario;tx.executeWithoutResult(status->{try{
                    var f=fixture(sql,tasks,media,files);var l=lease(sql,tasks,f,3);var p=media.preview(f.actor(),f.id()).orElseThrow();
                    long feeCount=sql.jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE scope_id IN (SELECT scope_id FROM fee_scopes WHERE actor_user_id=?)",Long.class,f.actor().userId());
                    var originalOps=media.operations(f.actor(),f.id());
                    if(test==0){
                        execute(media,exporter,l,p);var bundle=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                        check(tasks.read(f.actor(),f.id()).status().equals("WAITING_MEDIA_REVIEW"),"real_file_not_success_before_human_review");
                        check(tasks.read(f.actor(),f.id()).progress().percent()<100,"quality_wait_below_100");
                        check(bundle.check().structuralStatus().equals("PASSED")&&exporter.intact(bundle),"actual_pptx_and_all_pngs_reopened");
                        check(bundle.check().images().size()==2,"both_image_kinds_synthetic_metadata_bound");
                        var a=artifacts.artifact(f.actor(),bundle.artifactId());check(a.revision()==1&&a.previewVersion()==1&&a.mime().equals(Presentation.MIME),"binary_mime_revision_and_approval_link");
                        check(Arrays.equals(files.read(bundle.pptx()),context.getBean(ArtifactStorePort.class).bytes(f.actor(),bundle.artifactId())),"real_artifact_port_binary_checksum");
                        media.review(f.actor(),f.id(),1,true,"合成流程的SQL验收状态测试，不代表人工作品验收");check(tasks.read(f.actor(),f.id()).status().equals("SUCCEEDED"),"explicit_review_success_transition");
                        check(media.operations(f.actor(),f.id()).equals(originalOps)&&sql.jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE scope_id IN (SELECT scope_id FROM fee_scopes WHERE actor_user_id=?)",Long.class,f.actor().userId())==feeCount,"local_export_no_new_purchase_or_fee");
                        try{Files.write(EVIDENCE.resolve("synthetic-acceptance.pptx"),files.read(bundle.pptx()));Files.writeString(EVIDENCE.resolve("synthetic-check.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(bundle));}catch(IOException e){throw new IllegalStateException(e);}
                    }else if(test==1){
                        check(media.presentationStart(l,"f".repeat(64),false).isEmpty(),"interrupted_first_local_intent_persistent");
                        check(media.presentationStart(l,"f".repeat(64),false).isEmpty(),"second_local_build_allowed");
                        expect(()->media.presentationStart(l,"f".repeat(64),false),"PPT_EXPORT_EXHAUSTED","third_build_blocked_persistently");
                    }else if(test==2){
                        execute(media,exporter,l,p);var first=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                        try{Files.write(EVIDENCE.resolve("files").resolve(first.pptx().storageKey()),new byte[]{1,2,3});}catch(IOException e){throw new IllegalStateException(e);}
                        var recovered=lease(sql,tasks,f,4);execute(media,exporter,recovered,media.preview(f.actor(),f.id()).orElseThrow());var second=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                        check(exporter.intact(second)&&second.artifactId().equals(first.artifactId()),"damaged_actual_file_rebuilt_same_artifact");
                        check(sql.jdbc.queryForObject("SELECT attempt FROM presentation_exports WHERE task_id=?",Integer.class,f.id())==2,"rebuild_count_survives_lease_change");
                        check(media.operations(f.actor(),f.id()).equals(originalOps)&&sql.jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE scope_id IN (SELECT scope_id FROM fee_scopes WHERE actor_user_id=?)",Long.class,f.actor().userId())==feeCount,"recovery_original_images_and_fees_unchanged");
                    }else if(test==3){
                        var bundle=raw(media,exporter,l,p);tasks.action(f.actor(),f.id(),"cancel");
                        expect(()->media.presentationComplete(l,bundle),"STALE_EXECUTION","cancel_blocks_late_presentation_publish");
                        check(sql.jdbc.queryForObject("SELECT COUNT(*) FROM artifacts WHERE task_id=? AND kind='PPTX'",Integer.class,f.id())==0,"cancel_has_no_pptx_registration");
                    }else if(test==4){
                        execute(media,exporter,l,p);var b=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                        var other=actor(sql,"USER");var admin=actor(sql,"ADMIN");
                        expect(()->media.presentationCheck(other,f.id()),"ACCESS_DENIED","other_user_private_check_denied");expect(()->artifacts.artifact(admin,b.artifactId()),"ACCESS_DENIED","admin_private_pptx_denied");
                    }else if(test==5){
                        execute(media,exporter,l,p);long artifact=media.presentationCheck(f.actor(),f.id()).orElseThrow().artifactId();
                        sql.jdbc.update("UPDATE knowledge_bases SET enabled=FALSE WHERE id=?",f.base());expect(()->artifacts.artifact(f.actor(),artifact),"ACCESS_DENIED","source_revocation_blocks_real_file_download");
                    }else if(test==6){
                        var bundle=raw(media,exporter,l,p);sql.jdbc.update("UPDATE generation_previews SET status='INVALIDATED' WHERE task_id=?",f.id());
                        expect(()->media.presentationComplete(l,bundle),"OPERATION_CONFLICT","invalidated_approval_blocks_candidate_publish");
                    }else if(test==7){
                        execute(media,exporter,l,p);var b=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                        var revised=p.units().stream().map(u->new Media.Unit(u.unitId(),u.title()+"修订",u.text(),u.notes(),u.layout(),u.imageMode(),u.imagePrompt(),u.references(),u.seconds())).toList();
                        var edited=media.edit(f.actor(),f.id(),1,revised,CONFIG);check(edited.previewVersion()==2&&media.presentationCheck(f.actor(),f.id()).isEmpty(),"edit_invalidates_old_check_and_version");
                        expect(()->artifacts.artifact(f.actor(),b.artifactId()),"ACCESS_DENIED","edited_content_retracts_old_deck");
                        media.decide(f.actor(),edited.approvalId(),true,CONFIG,prices(),Map.of("IMAGE_GENERATION","synthetic-image"));check(media.operations(f.actor(),f.id()).get(0).operationId().equals(originalOps.get(0).operationId()),"new_approval_reuses_paid_operation");
                        var next=lease(sql,tasks,f,5);execute(media,exporter,next,media.preview(f.actor(),f.id()).orElseThrow());check(media.presentationCheck(f.actor(),f.id()).orElseThrow().check().previewVersion()==2,"revised_deck_has_new_approval");
                    }else if(test==8){
                        var bundle=raw(media,exporter,l,p);check(media.presentationCheck(f.actor(),f.id()).isEmpty(),"unregistered_file_not_published_check");
                        check(sql.jdbc.queryForObject("SELECT COUNT(*) FROM artifacts WHERE task_id=? AND kind='PPTX'",Integer.class,f.id())==0,"temporary_local_file_not_downloadable");
                        media.presentationComplete(l,bundle);var b=media.presentationCheck(f.actor(),f.id()).orElseThrow();media.review(f.actor(),f.id(),1,false,"合成拒绝状态测试");
                        check(tasks.read(f.actor(),f.id()).status().equals("PAUSED"),"human_rejection_pauses_without_purchase");expect(()->artifacts.artifact(f.actor(),b.artifactId()),"ACCESS_DENIED","rejected_candidate_download_blocked");
                    }else{
                        media.yield(l,"MEDIA_READY",null);sql.jdbc.update("UPDATE ai_tasks SET media_deadline=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",f.id());
                        expect(()->media.requestPresentation(f.actor(),f.id(),1),"BUDGET_EXCEEDED","old_deadline_never_reset_by_export");
                    }
                }finally{status.setRollbackOnly();}});
            }
            int port=((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)context).getWebServer().getPort();
            Fixture committed=tx.execute(s->fixture(sql,tasks,media,files));var outsiders=new ArrayList<UserContext>();
            try{
                tx.executeWithoutResult(s->{var l=lease(sql,tasks,committed,3);execute(media,exporter,l,media.preview(committed.actor(),committed.id()).orElseThrow());outsiders.add(actor(sql,"USER"));outsiders.add(actor(sql,"ADMIN"));});
                var token=token(context,committed.actor());var other=token(context,outsiders.get(0));var admin=token(context,outsiders.get(1));
                var bundle=media.presentationCheck(committed.actor(),committed.id()).orElseThrow();String path="/artifacts/"+bundle.artifactId();
                check(http(port,path,null).statusCode()==401,"real_http_anonymous_binary_401");check(http(port,path,other).statusCode()==403,"real_http_other_binary_403");check(http(port,path,admin).statusCode()==403,"real_http_admin_private_binary_403");
                var response=http(port,path,token);check(response.statusCode()==200&&response.headers().firstValue("Content-Type").orElse("").equals(Presentation.MIME),"real_http_sql_file_pptx_mime");
                check(response.headers().firstValue("Content-Disposition").orElse("").endsWith(".pptx"),"real_http_pptx_download_extension");
                check(Arrays.equals(response.body(),files.read(bundle.pptx()))&&response.headers().firstValue("X-Artifact-Checksum").orElse("").equals(bundle.pptx().checksum()),"real_http_sql_file_binary_and_checksum");
                check(http(port,"/tasks/"+committed.id()+"/presentation-check",token).statusCode()==200,"real_http_current_presentation_check");
                check(http(port,"/artifacts/"+bundle.pages().get(0).artifactId(),token).headers().firstValue("Content-Type").orElse("").equals("image/png"),"real_http_page_png_download");
                tx.executeWithoutResult(s->sql.jdbc.update("UPDATE knowledge_bases SET enabled=FALSE WHERE id=?",committed.base()));check(http(port,path,token).statusCode()==403,"real_http_source_revocation_403");
            }finally{tx.executeWithoutResult(s->cleanup(sql,committed,outsiders));}
            check(users.equals(sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class)),"fixture_users_removed_existing_preserved");check(taskIds.equals(sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class)),"fixture_tasks_removed_no_formal_queue_claim");
            check(upstream.equals(sql.jdbc.queryForList("SELECT id,status,model_attempts,model_turns FROM ai_tasks WHERE id IN (78,85) ORDER BY id")),"s09_media_tasks_and_budgets_unchanged");
            var result=new LinkedHashMap<String,Object>();result.put("passed",CHECKS.size());result.put("checks",CHECKS);result.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256"));
            result.put("database",sql.jdbc.queryForObject("SELECT VERSION()",String.class));result.put("migration",sql.jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE",Integer.class));result.put("paid_calls",0);
            result.put("scope","真实SQL＋本地文件／渲染＋HTTP；合成笔记／图片／价格／审批，未执行真实Planner、真实生图／事实检索、人工PowerPoint质量验收或OS强杀");
            Files.writeString(EVIDENCE.resolve("native-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));System.out.println("S10_CHECKS="+CHECKS.size());
        }
    }
    /** 新建当前专项用户，不修改已有账号密码。 */
    private static UserContext actor(ValidationSql sql,String role){long id=sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,'unused-s10',?,FALSE)","s10_"+UUID.randomUUID().toString().replace("-",""),role);return new UserContext(id,UserContext.Role.valueOf(role),true,1,false);}
    /** 来源与图片都是明确合成夹具，正规持久端口保存审批／操作／费用。 */
    private static Fixture fixture(ValidationSql sql,TaskStorePort tasks,MediaStorePort media,MediaFilePort files){
        var actor=actor(sql,"USER");long base=sql.insert("INSERT INTO knowledge_bases(owner_user_id,name) VALUES(?,'S10合成资料')",actor.userId());
        long doc=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,'水循环合成说明','txt')",base,actor.userId());String raw="水受热形成水汽，水汽遇冷凝成水滴。";
        sql.jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum,ingestion_status) VALUES(?,1,?,?,'READY')",doc,raw,SqlSupport.hash(raw));
        var request=new TaskRequest("NOTES_PPT","水循环合成验收",ScopeRequest.self(),List.of(doc),UUID.randomUUID().toString(),"PLANNED",new Media.PresentationOptions(3,"default",BigDecimal.TEN),null);
        long id=tasks.create(actor,request).taskId();var f=new Fixture(actor,request,id,base,doc);var l=lease(sql,tasks,f,1);
        var units=List.of(new Media.Unit("slide-1","水循环","水受热形成水汽。","讲解蒸发机制。合成素材仅用于程序验收。","IMAGE_TEXT","GENERATED","合成图片提示",List.of("D"+doc+"v1"),0),new Media.Unit("slide-2","凝结","水汽遇冷，凝成水滴。","讲解凝结。该配图为合成占位测试图。","IMAGE_TEXT","WEB_SEARCH","合成事实候选",List.of("D"+doc+"v1"),0));
        byte[] png=png();var web=new Media.Asset(UUID.randomUUID().toString(),"slide-2","WEB_SEARCH",null,files.writePagePreview(UUID.randomUUID().toString(),png),new Media.ImageSource("fixture","合成查询","https://example.invalid/source","https://example.invalid/image","合成测试来源","合成作者","合成测试许可","合成对象，非真实事实图",Instant.now()),null);media.webAsset(l,web);
        var plan=new Media.Plan(1,"media-plan-s09-v1",List.of(new Media.Step("content","content","PresentationContentWorker",List.of(),List.of("SOURCE"),"ALWAYS","合成"),new Media.Step("layout","layout","PresentationLayoutWorker",List.of("content"),List.of("content"),"ALWAYS","合成"),new Media.Step("review","review","TeachingReviewWorker",List.of("layout"),List.of("layout"),"ALWAYS","合成")));
        media.plan(l,new Media.PlanSnapshot(plan,SqlSupport.hash(encode(plan)),"SQL_FIXTURE","S10_TEST"));
        var p=new Media.Preview(id,1,1,"b".repeat(64),"WAITING",UUID.randomUUID().toString(),Instant.now().plusSeconds(1800),CONFIG,"CNY",new BigDecimal("0.1"),BigDecimal.TEN,units,List.of(new SourceDependency(base,doc,1)),List.of(),List.of(),List.of(web),"SQL_FIXTURE");
        media.prepare(l,p);media.decide(actor,p.approvalId(),true,CONFIG,prices(),Map.of("IMAGE_GENERATION","synthetic-image"));l=lease(sql,tasks,f,2);
        var op=media.operations(actor,id).get(0);media.sending(l,op.operationId());media.received(op.operationId(),new Media.ProviderResult("synthetic-"+UUID.randomUUID(),null,"synthetic-image","SUCCESS",List.of("https://example.invalid/image"),List.of(),1L,null));
        media.publishAsset(l,op.operationId(),new Media.Asset(op.operationId(),"slide-1","GENERATED",op.operationId(),files.writePagePreview(UUID.randomUUID().toString(),png),null,null));return f;
    }
    /** 只有本次明确ID获得测试租约，不调用全队列claim。 */
    private static TaskLease lease(ValidationSql sql,TaskStorePort tasks,Fixture f,int fence){sql.jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s10-fixture',fencing_token=?,claimed_at=CURRENT_TIMESTAMP(6),lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",fence,f.id());return new TaskLease(tasks.read(f.actor(),f.id()),f.request(),f.actor(),"s10-fixture",fence);}
    /** 直接调用正式本地执行器，绝无Provider注入或调用。 */
    private static void execute(MediaStorePort media,PresentationPort exporter,TaskLease l,Media.Preview p){new PresentationExecution(media,exporter).execute(l,p);}
    /** 构造未登记候选供取消／旧批准失败路径，仍先持久消费本地额度。 */
    private static Presentation.Bundle raw(MediaStorePort media,PresentationPort exporter,TaskLease l,Media.Preview p){String hash="f".repeat(64);media.presentationStart(l,hash,false);return exporter.export(p,hash,media.deadline(l),209715200);}
    /** 合成纯色PNG只检验二进制封装，不作为真实照片／生成结果证据。 */
    private static byte[] png(){try{var out=new ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(16,16,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);return out.toByteArray();}catch(IOException e){throw new IllegalStateException(e);}}
    /** 合成固定价格只验证账本保持，不当作实际报价。 */
    private static Map<String,FeePrice> prices(){return Map.of("IMAGE_GENERATION",new FeePrice("synthetic","v1","CNY","PER_IMAGE",Instant.parse("2026-01-01T00:00:00Z"),new BigDecimal("0.1"),BigDecimal.ZERO));}
    /** 夹具也使用真实类型JSON摘要，不能绕过持久计划校验。 */
    private static String encode(Object value){try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("夹具序列化失败",e);}}
    /** 进程一保存正式导出事实并退出；进程二从原批准／原ID恢复损坏文件后精确清理。 */
    private static void restart(String mode,ValidationSql sql,TaskStorePort tasks,MediaStorePort media,MediaFilePort files,PresentationPort exporter,TransactionTemplate tx)throws Exception{
        Path statePath=EVIDENCE.resolve("restart-state.json");
        if(mode.equals("restart-write")||mode.equals("restart-crash-wait")){
            if(Files.exists(statePath))throw new IllegalStateException("已有恢复夹具，先执行restart-read清理，不能再创建");
            String users=SqlSupport.hash(encode(sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class)));String taskIds=SqlSupport.hash(encode(sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class)));
            Fixture f=tx.execute(s->fixture(sql,tasks,media,files));
            try{
                tx.executeWithoutResult(s->{var l=lease(sql,tasks,f,3);execute(media,exporter,l,media.preview(f.actor(),f.id()).orElseThrow());media.review(f.actor(),f.id(),1,false,"跨进程恢复合成状态测试，不代表本人观看结论");});
                var b=media.presentationCheck(f.actor(),f.id()).orElseThrow();
                var state=new RestartState(f,ProcessHandle.current().pid(),b.artifactId(),SqlSupport.hash(encode(media.operations(f.actor(),f.id()))),feeHash(sql,f),sql.jdbc.queryForObject("SELECT media_deadline FROM ai_tasks WHERE id=?",String.class,f.id()),tasks.read(f.actor(),f.id()).modelAttempts(),users,taskIds,System.getProperty("validation.application-jar-sha256"));
                Files.writeString(statePath,JSON.writerWithDefaultPrettyPrinter().writeValueAsString(state));
                Files.write(EVIDENCE.resolve("files").resolve(b.pptx().storageKey()),new byte[]{1,2,3});System.out.println("S10_RESTART_WRITE_PERSISTED");
                if(mode.equals("restart-crash-wait")){
                    // 持久快照和故障文件都写完后才通知父脚本。等待只用于强杀本次合成进程。
                    Files.writeString(EVIDENCE.resolve("crash-ready.json"),encode(Map.of("pid",ProcessHandle.current().pid(),"jarHash",state.jarHash())));
                    Thread.sleep(120_000);
                    throw new IllegalStateException("父脚本未在期限内终止合成进程");
                }
            }catch(Exception e){tx.executeWithoutResult(s->cleanup(sql,f,List.of()));Files.deleteIfExists(statePath);throw e;}
        }else if(mode.equals("restart-read")){
            var state=JSON.readValue(Files.readString(statePath),RestartState.class);var f=state.fixture();
            // 包不一致时保留状态和夹具，不能在finally中删除供旧包恢复所需的事实。
            check(Objects.equals(state.jarHash(),System.getProperty("validation.application-jar-sha256")),"restart_same_final_application_package");
            try{
                check(state.processId()!=ProcessHandle.current().pid(),"distinct_actual_jvm_restart");
                var old=media.presentationCheck(f.actor(),f.id()).orElseThrow();check(!exporter.intact(old),"previous_process_corrupt_file_detected");
                media.requestPresentation(f.actor(),f.id(),1);tx.executeWithoutResult(s->{var l=lease(sql,tasks,f,4);execute(media,exporter,l,media.preview(f.actor(),f.id()).orElseThrow());});
                var b=media.presentationCheck(f.actor(),f.id()).orElseThrow();check(exporter.intact(b)&&b.artifactId()==state.artifactId(),"new_process_rebuild_same_private_artifact");
                check(sql.jdbc.queryForObject("SELECT attempt FROM presentation_exports WHERE task_id=?",Integer.class,f.id())==2,"actual_restart_preserves_two_build_limit");
                check(state.operationHash().equals(SqlSupport.hash(encode(media.operations(f.actor(),f.id()))))&&state.feeHash().equals(feeHash(sql,f)),"actual_restart_original_operations_and_ledger_unchanged");
                check(state.deadline().equals(sql.jdbc.queryForObject("SELECT media_deadline FROM ai_tasks WHERE id=?",String.class,f.id()))&&state.modelAttempts()==tasks.read(f.actor(),f.id()).modelAttempts(),"actual_restart_deadline_and_shared_attempts_not_reset");
            }finally{tx.executeWithoutResult(s->cleanup(sql,f,List.of()));Files.deleteIfExists(statePath);}
            check(state.usersBefore().equals(SqlSupport.hash(encode(sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class))))&&state.tasksBefore().equals(SqlSupport.hash(encode(sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class)))),"restart_fixture_cleaned_existing_users_tasks_preserved");
            Files.writeString(EVIDENCE.resolve("restart-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("passed",CHECKS.size(),"checks",CHECKS,"application_jar_sha256",System.getProperty("validation.application-jar-sha256"),"paid_calls",0,"scope",Files.exists(EVIDENCE.resolve("crash-killed.json"))?"本次JVM在持久检查点后被OS强杀；合成SQL执行权／图片／批准，不证明正式claim扫描或Provider调用":"两个真实JVM；合成SQL执行权／图片／批准；不是OS强杀或Provider调用")));System.out.println("S10_RESTART_CHECKS="+CHECKS.size());
        }else throw new IllegalArgumentException("未知恢复阶段");
    }
    /** 原可靠账本字段摘要，不包含凭证或模型正文。 */
    private static String feeHash(ValidationSql sql,Fixture f){return SqlSupport.hash(encode(sql.jdbc.queryForList("SELECT operation_id,state,reserved_amount,estimated_amount,used_units FROM fee_attempts WHERE scope_id IN (SELECT scope_id FROM fee_scopes WHERE actor_user_id=?) ORDER BY operation_id",f.actor().userId())));}
    /** 测试Bearer只存哈希与内存，不输出或写入证据。 */
    private static String token(org.springframework.context.ApplicationContext context,UserContext actor){String raw=UUID.randomUUID().toString()+UUID.randomUUID();context.getBean(AuthTokenStorePort.class).issue(context.getBean(UserStorePort.class).user(actor.userId()).orElseThrow(),AccountApplicationService.digest(raw),Instant.now().plusSeconds(300));return raw;}
    /** 真网络请求保持二进制，不将PPTX转UTF-8。 */
    private static HttpResponse<byte[]> http(int port,String path,String token)throws Exception{var r=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(Duration.ofSeconds(10));if(token!=null)r.header("Authorization","Bearer "+token);return HttpClient.newHttpClient().send(r.GET().build(),HttpResponse.BodyHandlers.ofByteArray());}
    /** 仅删除这次提交的精确夹具ID，外部费用／原用户／正式队列不触碰。 */
    private static void cleanup(ValidationSql sql,Fixture f,List<UserContext> others){
        for(String table:List.of("presentation_exports","media_human_reviews","media_preview_operations","media_attempts","media_assets")){
            if(table.equals("media_attempts"))sql.jdbc.update("DELETE FROM media_attempts WHERE operation_id IN (SELECT operation_id FROM media_operations WHERE task_id=?)",f.id());else sql.jdbc.update("DELETE FROM "+table+" WHERE task_id=?",f.id());
        }
        sql.jdbc.update("DELETE FROM artifacts WHERE task_id=?",f.id());sql.jdbc.update("DELETE FROM media_operations WHERE task_id=?",f.id());
        sql.jdbc.update("DELETE FROM fee_attempts WHERE scope_id IN (SELECT scope_id FROM fee_scopes WHERE actor_user_id=?)",f.actor().userId());sql.jdbc.update("DELETE FROM fee_scopes WHERE actor_user_id=?",f.actor().userId());
        for(String table:List.of("generation_previews","media_worker_results","media_plans","task_document_pages","task_document_coverage","task_steps","task_step_progress","task_plans"))sql.jdbc.update("DELETE FROM "+table+" WHERE task_id=?",f.id());
        sql.jdbc.update("DELETE FROM request_deduplications WHERE actor_user_id=?",f.actor().userId());sql.jdbc.update("DELETE FROM ai_tasks WHERE id=?",f.id());
        sql.jdbc.update("DELETE FROM knowledge_access_audit WHERE actor_user_id=?",f.actor().userId());sql.jdbc.update("DELETE FROM document_versions WHERE document_id=?",f.doc());sql.jdbc.update("DELETE FROM documents WHERE id=?",f.doc());sql.jdbc.update("DELETE FROM knowledge_bases WHERE id=?",f.base());
        var actors=new ArrayList<>(others);actors.add(f.actor());for(var a:actors){sql.jdbc.update("DELETE FROM auth_tokens WHERE user_id=?",a.userId());sql.jdbc.update("DELETE FROM users WHERE id=?",a.userId());}
    }
    /** 预期错误按准确码核验，失败不转为购买许可。 */
    private static void expect(Runnable action,String code,String label){try{action.run();throw new AssertionError(label);}catch(LabException e){if(!e.code().equals(code))throw new AssertionError(label+"："+e.code());check(true,label);}}
    /** 只输出标签／数量，不记录凭证、提示词或用户资料正文。 */
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);CHECKS.add(label);}
}
