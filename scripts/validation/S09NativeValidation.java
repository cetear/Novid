package com.example.ailab.app;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** 真实MySQL回滚夹具验证；关闭所有Worker，零外部模型／媒体调用，不领取现有任务。 */
public final class S09NativeValidation {
    private static final List<String> CHECKS=new ArrayList<>();
    private static final String CONFIG="a".repeat(64);
    private record Fixture(UserContext actor,TaskRequest request,long id,Media.Preview preview) { }
    /** 正式包启动执行Flyway校验及追加迁移，测试数据全部位于显式回滚事务。 */
    public static void main(String[] args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));var app=new SpringApplication(LabApplication.class);LocalEnvironmentLoader.initialize(app,Path.of(".env"));
        try(var context=app.run("--server.port=0","--server.address=127.0.0.1","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false","--lab.media.worker-enabled=false",
                "--lab.ingestion.worker-enabled=false","--lab.governance.cleanup-enabled=false","--lab.observability.export-enabled=false","--lab.model.mode=mock","--lab.search.enabled=false","--spring.main.banner-mode=off","--logging.level.root=OFF")){
            var sql=ValidationSql.from(context);var tasks=context.getBean(TaskStorePort.class);var media=context.getBean(MediaStorePort.class);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var users=sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class);var taskIds=sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class);
            for(int scenario=0;scenario<6;scenario++){
                final int test=scenario;tx.executeWithoutResult(status->{try{
                    var f=fixture(sql,tasks,media,test==0?3:1,test==4);var approved=media.decide(f.actor(),f.preview().approvalId(),true,CONFIG,prices(),Map.of());
                    var ops=media.operations(f.actor(),f.id());check(ops.size()==(test==0?3:1),"native_has_no_tts_intent_"+test);
                    media.decide(f.actor(),f.preview().approvalId(),true,CONFIG,prices(),Map.of());check(media.operations(f.actor(),f.id()).size()==ops.size(),"duplicate_approval_no_purchase_"+test);
                    var lease=lease(sql,tasks,f,2);check(media.deadline(lease).isAfter(Instant.now()),"shared_deadline_"+test);
                    var op=ops.stream().filter(o->o.capability().equals("VIDEO_GENERATION")).findFirst().orElseThrow();
                    var submission=media.sending(lease,op.operationId());check(submission.video().capability().id().equals("fixture-api"),"approved_route_persisted_"+test);
                    check(sql.jdbc.queryForObject("SELECT provider_namespace FROM media_operations WHERE operation_id=?",String.class,op.operationId()).equals("fixture-account"),"account_namespaced_original_id_"+test);
                    if(test==0){
                        fixture(sql,tasks,media,1);check(true,"same_catalog_survives_mysql_json_normalization");
                        media.received(op.operationId(),new Media.ProviderResult("original-provider-id",null,"fixture","PROCESSING",List.of(),List.of(),null,null));
                        check(media.operations(f.actor(),f.id()).stream().filter(x->x.operationId().equals(op.operationId())).findFirst().orElseThrow().providerJobId().equals("original-provider-id"),"original_id_durable_before_query");
                        sql.jdbc.update("UPDATE media_operations SET next_poll_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE operation_id=?",op.operationId());media.polling(lease,op.operationId());
                        check(media.operations(f.actor(),f.id()).stream().filter(x->x.operationId().equals(op.operationId())).findFirst().orElseThrow().pollCount()==1,"query_count_persistent");
                        expect(()->media.sending(lease,op.operationId()),"sent_operation_never_gets_second_permit");
                    }else if(test==1){
                        media.received(op.operationId(),new Media.ProviderResult(null,null,null,"UNKNOWN",List.of(),List.of(),null,"MEDIA_SUBMISSION_UNKNOWN"));media.yield(lease,"NEEDS_RECONCILIATION","MEDIA_SUBMISSION_UNKNOWN");
                        expect(()->tasks.action(f.actor(),f.id(),"resume"),"missing_id_blocks_resume_repurchase");
                    }else if(test==2){
                        media.received(op.operationId(),new Media.ProviderResult("job",null,"fixture","SUCCESS",List.of("https://example.invalid/video"),List.of(),5L,null));
                        var fact=new Media.FileFact(op.operationId()+".mp4","video/mp4",100,"b".repeat(64));
                        media.publishAsset(lease,op.operationId(),new Media.Asset(op.operationId(),op.unitId(),"VIDEO_CLIP",op.operationId(),fact,null,null));media.measured(lease,op.unitId(),5000);
                        check(media.shots(f.actor(),f.id()).get(0).audioDurationMs()==5000,"native_measured_fact_persistent");
                        media.yield(lease,"NEEDS_RECONCILIATION","LOCAL_FIXTURE");
                        var edited=media.edit(f.actor(),f.id(),1,f.preview().units(),CONFIG);media.decide(f.actor(),edited.approvalId(),true,CONFIG,prices(),Map.of());
                        check(media.operations(f.actor(),f.id()).get(0).operationId().equals(op.operationId()),"unchanged_success_reused_after_reapproval");
                        check(sql.jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE operation_id=?",Integer.class,op.operationId())==1,"reused_media_no_second_fee");
                    }else if(test>=4){
                        media.received(op.operationId(),new Media.ProviderResult("audio-policy-job",null,"fixture","SUCCESS",List.of("https://example.invalid/video"),List.of(),5L,null));
                        var fact=new Media.FileFact(op.operationId()+".mp4","video/mp4",100,"b".repeat(64));
                        media.publishAsset(lease,op.operationId(),new Media.Asset(op.operationId(),op.unitId(),"VIDEO_CLIP",op.operationId(),fact,null,null));
                        if(test==4){media.measured(lease,op.unitId(),0);check(media.shots(f.actor(),f.id()).get(0).audioDurationMs()==0,"silent_zero_is_measured_not_unknown");}
                        else expect(()->media.measured(lease,op.unitId(),0),"narrated_native_without_audio_rejected");
                    }else{
                        long other=sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,'unused-fixture','USER',FALSE)","s09_"+UUID.randomUUID().toString().replace("-",""));
                        var outsider=new UserContext(other,UserContext.Role.USER,true,1,false);expect(()->media.preview(outsider,f.id()),"cross_owner_preview_denied");
                    }
                }finally{status.setRollbackOnly();}});
            }
            check(users.equals(sql.jdbc.queryForList("SELECT id FROM users ORDER BY id",Long.class)),"own_fixture_users_rolled_back");check(taskIds.equals(sql.jdbc.queryForList("SELECT id FROM ai_tasks ORDER BY id",Long.class)),"existing_tasks_unchanged_no_queue_claim");
            var evidence=new LinkedHashMap<String,Object>();evidence.put("passed",CHECKS.size());evidence.put("checks",CHECKS);evidence.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256"));evidence.put("database",sql.jdbc.queryForObject("SELECT VERSION()",String.class));evidence.put("migration",sql.jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE",Integer.class));evidence.put("paid_calls",0);evidence.put("scope","真实SQL事务＋合成路由／价格／文件元数据；不是实际提供方生成、实际文件或人工验收");
            Files.writeString(Path.of("var/stage-S09/native-results.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));System.out.println("S09_MYSQL_CHECKS="+CHECKS.size());
        }
    }
    /** 不调用claim，显式执行权只属于当前事务新建的任务。 */
    private static TaskLease lease(ValidationSql sql,TaskStorePort tasks,Fixture f,int fence){sql.jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s09-fixture',fencing_token=?,claimed_at=CURRENT_TIMESTAMP(6),lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",fence,f.id());return new TaskLease(tasks.read(f.actor(),f.id()),f.request(),f.actor(),"s09-fixture",fence);}
    /** 所有来源及脚本为SQL层合成夹具，故不宣称业务层真实资料规划已验。 */
    private static Fixture fixture(ValidationSql sql,TaskStorePort tasks,MediaStorePort media,int count){
        return fixture(sql,tasks,media,count,false);
    }
    /** 无声场景台词为空，验证新增数据库约束与正式审批链。 */
    private static Fixture fixture(ValidationSql sql,TaskStorePort tasks,MediaStorePort media,int count,boolean silent){
        long actorId=sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,'unused-fixture','USER',FALSE)","s09_"+UUID.randomUUID().toString().replace("-",""));var actor=new UserContext(actorId,UserContext.Role.USER,true,1,false);
        var request=new TaskRequest("NOTES_VIDEO","SQL合成镜头",new ScopeRequest(ScopeRequest.Mode.SELF,List.of(),null),List.of(),UUID.randomUUID().toString(),"PLANNED",null,new Media.VideoOptions("c","v","s",count*5,new BigDecimal("30"),count));
        var task=tasks.create(actor,request);var units=new ArrayList<Media.Unit>();var choices=new ArrayList<VideoApi.Selection>();
        var capability=new VideoApi.Capability("fixture-api",1,CONFIG,"fixture","fixture-account","fixture-model","test",List.of(5),List.of("720P"),List.of("NATIVE","NONE"),Map.of("720P",prices().get("VIDEO_GENERATION")),"PROTOCOL_TESTED",15,60);
        for(int i=1;i<=count;i++){units.add(new Media.Unit("shot-"+i,"合成",silent||i==2?"":"这是测试台词","动作","SCENE","NONE","测试画面",List.of("D1v1"),5));choices.add(new VideoApi.Selection(capability,"720P",silent||i==2?"NONE":"NATIVE",5,"SQL夹具选择"));}
        var catalog=new Media.CatalogItem("sql-catalog-fixture","CHARACTER",1,"合成",true,"fixture","PROMPT_GUIDANCE","合成",Map.of("fixture-model/PROMPT_GUIDANCE","合成"));
        var board=StoryboardRules.routed(1,units,choices);var preview=new Media.Preview(task.taskId(),1,1,board.hash(),"WAITING",UUID.randomUUID().toString(),Instant.now().plusSeconds(1800),CONFIG,"CNY",new BigDecimal("2"),new BigDecimal("30"),units,List.of(),List.of(),List.of(catalog),List.of(),"SQL_FIXTURE",board);
        var f=new Fixture(actor,request,task.taskId(),preview);media.prepare(lease(sql,tasks,f,1),preview);return f;
    }
    /** 固定价格仅供SQL算术，不是实际账号报价。 */
    private static Map<String,FeePrice> prices(){return Map.of("VIDEO_GENERATION",new FeePrice("fixture-video","v1","CNY","PER_SECOND",Instant.parse("2026-01-01T00:00:00Z"),new BigDecimal("0.1"),BigDecimal.ZERO));}
    /** 预期拒绝在该回滚场景末尾执行，避免吞掉事务异常后伪报提交成功。 */
    private static void expect(Runnable call,String label){try{call.run();throw new AssertionError(label);}catch(LabException expected){check(true,label);}}
    /** 只输出检查标识，不打印用户资料、凭证或请求体。 */
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);CHECKS.add(label);}
}
