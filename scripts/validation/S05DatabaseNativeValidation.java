package com.example.ailab.app;

import com.example.ailab.business.application.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.*;
import com.example.ailab.data.repository.SqlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** 真实MySQL短事务验收，只有新建合成用户／资料／任务，租约夹具明确不是队列实测。 */
public final class S05DatabaseNativeValidation {
    private static final List<String> CHECKS = new ArrayList<>();
    private static final String PASSWORD = "s05-synthetic-validation-password";
    /** 追加迁移V8正常启动；关闭队列、采集、ES与真实模型，夹具全部回滚。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        var applicationBoot = new SpringApplication(LabApplication.class);
        // 专项同样走正式.env初始化；直接SpringApplication.run不会调用LabApplication.main。
        LocalEnvironmentLoader.initialize(applicationBoot,Path.of(".env"));
        try (var context = applicationBoot.run(
                "--lab.search.enabled=false","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false",
                "--lab.ingestion.worker-enabled=false","--lab.model.mode=mock",
                "--spring.main.web-application-type=none","--spring.main.banner-mode=off","--logging.level.root=OFF")) {
            var jdbc = context.getBean(JdbcTemplate.class); var sql = context.getBean(SqlSupport.class);
            var before = counts(jdbc);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                try {
                    var actor = actor(context.getBean(AccountApplicationService.class),sql);
                    var observer = actor(context.getBean(AccountApplicationService.class),sql);
                    var tasks = context.getBean(TaskStorePort.class); var sessions = context.getBean(SessionStorePort.class);
                    var application = context.getBean(TaskApplicationService.class);
                    var base = context.getBean(KnowledgeBaseApplicationService.class).create(actor,"S05合成验证","");
                    var document = context.getBean(DocumentApplicationService.class).upload(actor,base.id(),"s05.txt","text/plain",
                            "合成资料，不含用户原文。".getBytes(StandardCharsets.UTF_8),"s05-doc");
                    var request = new TaskRequest("RESEARCH_REPORT","合成规划",ScopeRequest.self(),List.of(document.id()),"s05-task","PLANNED");
                    var task = application.create(actor,request);
                    check(application.create(actor,request).taskId()==task.taskId(),"planned_idempotency");
                    var lease = lease(jdbc,tasks,actor,task,request);
                    check(tasks.plan(lease).isEmpty(),"no_plan_before_generation");
                    var plan = plan(false); tasks.savePlan(lease,plan,"synthetic","routing-s05-v1");
                    tasks.savePlan(lease,plan,"synthetic","routing-s05-v1");
                    var snapshot = tasks.readPlan(actor,task.taskId()).orElseThrow();
                    check(snapshot.plan().equals(plan)&&snapshot.planHash().length()==64,"plan_roundtrip_and_hash");
                    denied("OPERATION_CONFLICT",() -> tasks.savePlan(lease,plan(true),"synthetic","routing-s05-v1"));
                    denied("ACCESS_DENIED",() -> tasks.readPlan(observer,task.taskId()));
                    for(int n=0;n<8;n++) tasks.reserveToolCall(lease);
                    denied("BUDGET_EXCEEDED",() -> tasks.reserveToolCall(lease));
                    tasks.reserveModelRepair(lease);
                    denied("MODEL_REPAIR_EXHAUSTED",() -> tasks.reserveModelRepair(lease));
                    tasks.beginStep(lease,"prepare"); tasks.completePreparation(lease);
                    tasks.beginStep(lease,"research");
                    tasks.checkpoint(lease,new TaskCheckpoint("research","合成研究",List.of(new SourceDependency(base.id(),document.id(),1)),false));
                    tasks.action(actor,task.taskId(),"pause");
                    denied("STALE_EXECUTION",() -> tasks.savePlan(lease,plan,"synthetic","routing-s05-v1"));
                    denied("STALE_EXECUTION",() -> tasks.reserveToolCall(lease));
                    denied("STALE_EXECUTION",() -> tasks.checkpoint(lease,new TaskCheckpoint("analysis","迟到",List.of(),false)));
                    tasks.action(actor,task.taskId(),"resume");
                    var resumed = lease(jdbc,tasks,actor,task,request);
                    check(tasks.plan(resumed).orElseThrow().equals(plan)&&tasks.checkpoints(resumed).stream().anyMatch(c -> c.stepId().equals("research")),"resume_keeps_plan_and_checkpoint");
                    check(jdbc.queryForObject("SELECT tool_calls FROM ai_tasks WHERE id=?",Integer.class,task.taskId())==8
                            && jdbc.queryForObject("SELECT model_repairs FROM ai_tasks WHERE id=?",Integer.class,task.taskId())==1,"resume_keeps_consumed_budgets");
                    tasks.action(actor,task.taskId(),"cancel");
                    denied("STALE_EXECUTION",() -> tasks.beginStep(resumed,"report"));
                    var fixedRequest = new TaskRequest("FAQ","合成固定流程",ScopeRequest.self(),List.of(document.id()),"s05-fixed");
                    var fixed = application.create(actor,fixedRequest);
                    check(fixed.progress().totalSteps()==5&&tasks.readPlan(actor,fixed.taskId()).isEmpty(),"fixed_faq_five_steps_compatible");
                    var session = sessions.create(actor,"合成工具历史","s05-session");
                    var execution = sessions.begin(actor,session.id(),session.version(),ScopeRequest.self());
                    var event = new ToolExchange("call-s05","get_knowledge_statistics","v1","{}","{\"status\":\"SUCCESS\",\"result\":\"synthetic\"}");
                    var answer = new AiResult("SUCCESS","合成答案",List.of(),"s05","synthetic",0,true,null);
                    denied("INVALID_ARGUMENTS",() -> sessions.complete(actor,execution,"合成问题",answer,List.of(),List.of(),null,Instant.now().plusSeconds(60),List.of(event,event)));
                    check(sessions.messages(actor,session.id(),0,100).isEmpty(),"invalid_pair_no_partial_history");
                    var committed = sessions.complete(actor,execution,"合成问题",answer,List.of(new SourceDependency(base.id(),document.id(),1)),List.of(),null,Instant.now().plusSeconds(60),List.of(event));
                    var events = sessions.messages(actor,session.id(),0,100);
                    check(events.stream().map(SessionMessage::role).toList().equals(List.of("USER","TOOL_REQUEST","TOOL_RESULT","ASSISTANT")),"four_events_atomic_and_ordered");
                    check(events.get(1).toolCallId().equals("call-s05")&&events.get(2).toolCallId().equals("call-s05"),"tool_id_preserved_in_sql");
                    sessions.verifyDelivery(actor,session.id(),committed.version());
                    denied("ACCESS_DENIED",() -> sessions.messages(observer,session.id(),0,100));
                    denied("STALE_EXECUTION",() -> sessions.complete(actor,execution,"迟到",answer,List.of(),List.of(),null,Instant.now().plusSeconds(60),List.of(event)));
                    check(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE session_id=?",Long.class,session.id())==4,"late_commit_leaves_no_extra_events");
                } finally { status.setRollbackOnly(); }
            });
            check(counts(jdbc).equals(before),"all_fixture_rows_rolled_back");
        }
        var evidence = Map.of("layer","REAL_MYSQL_SYNTHETIC_FIXTURES","application_jar_sha256",System.getProperty("validation.application-jar-sha256"),
                "checks",CHECKS,"passed",CHECKS.size(),"external_model_calls",0,"fixture_lease_simulated",true,"fixtures_rolled_back",true);
        Files.createDirectories(Path.of("var/stage-S05"));
        Files.writeString(Path.of("var/stage-S05/database-native-results.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println(new ObjectMapper().writeValueAsString(evidence));
    }
    /** 新建合成管理员并认证，不读取任何已有账户。 */
    private static UserContext actor(AccountApplicationService accounts,SqlSupport sql) {
        String name="s05-test-"+UUID.randomUUID().toString().substring(0,12);
        sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,'ADMIN',FALSE)",name,new BCryptPasswordEncoder(12).encode(PASSWORD));
        return accounts.authenticate(accounts.login(name,PASSWORD).token());
    }
    /** 只对本事务新任务模拟领取，不触碰全局队列和已有用户任务。 */
    private static TaskLease lease(JdbcTemplate jdbc,TaskStorePort tasks,UserContext actor,TaskSnapshot task,TaskRequest request) {
        jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s05-fixture',state_version=state_version+1,fencing_token=fencing_token+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),claimed_at=CURRENT_TIMESTAMP(6),started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=? AND requester_user_id=?",task.taskId(),actor.userId());
        long token=jdbc.queryForObject("SELECT fencing_token FROM ai_tasks WHERE id=?",Long.class,task.taskId());
        return new TaskLease(tasks.read(actor,task.taskId()),request,actor,"s05-fixture",token);
    }
    /** 合法独立或串行依赖夹具，只证明仓库存储而非模型规划。 */
    private static TaskPlan plan(boolean dependent) {
        return new TaskPlan("plan-s05-v1",List.of(
                new TaskPlan.Node("research","research","ResearchWorker","RESEARCH_REPORT",List.of(),Map.of("focus","研究"),"SOURCE_GROUNDED"),
                new TaskPlan.Node("analysis","analysis","AnalysisWorker","RESEARCH_REPORT",dependent?List.of("research"):List.of(),Map.of("focus","统计"),"SOURCE_GROUNDED"),
                new TaskPlan.Node("report","report","ReportWriter","RESEARCH_REPORT",List.of("research","analysis"),Map.of("focus","汇合"),"SOURCE_GROUNDED")));
    }
    /** 回滚前后只核对行数，不导出已有用户正文。 */
    private static Map<String,Long> counts(JdbcTemplate jdbc) {
        var result=new LinkedHashMap<String,Long>();
        for(String table:List.of("users","knowledge_bases","documents","ai_tasks","task_plans","task_steps","messages","sessions","artifacts"))
            result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));
        return result;
    }
    /** 精确断言与错误码，不把跳过算通过。 */
    private static void check(boolean condition,String name) { if(!condition) throw new AssertionError(name); CHECKS.add(name); }
    /** 预期失败也必须精确匹配业务错误。 */
    private static void denied(String code,Runnable action) {
        try { action.run(); } catch(LabException failure) { check(code.equals(failure.code()),"reject_"+code); return; }
        throw new AssertionError("expected_"+code);
    }
}
