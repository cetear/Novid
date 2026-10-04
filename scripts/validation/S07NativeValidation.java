package com.example.ailab.app;

import com.example.ailab.ai.model.*;
import com.example.ailab.contract.context.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.SqlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** S07真实MySQL短事务／并发，以及真实模型→正式费用账本；关闭所有队列与ES，不消费用户资料。 */
public final class S07NativeValidation {
    private static final List<String> CHECKS=new ArrayList<>();
    private static final List<FeeSummary> REAL=new ArrayList<>();
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    /** 合成用户唯一前缀，真实调用在数据库事务之外；finally只清理本次已知ID。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        boolean databaseOnly=Arrays.asList(args).contains("--database-only");
        var app=new SpringApplication(LabApplication.class);LocalEnvironmentLoader.initialize(app,Path.of(".env"));
        var options=new ArrayList<>(List.of("--lab.search.enabled=false","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false","--lab.ingestion.worker-enabled=false","--lab.observability.export-enabled=false","--spring.main.web-application-type=none","--spring.main.banner-mode=off","--logging.level.root=OFF","--lab.model.models.primary.output-limit=256","--lab.model.models.backup.output-limit=256"));
        if(databaseOnly)options.add("--lab.model.mode=mock");
        try(var context=app.run(options.toArray(String[]::new))) {
            var sql=context.getBean(SqlSupport.class);var jdbc=sql.jdbc;var fees=context.getBean(FeeStorePort.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var actor=tx.execute(s->actor(sql,"USER"));var admin=tx.execute(s->actor(sql,"ADMIN"));
            try {
                database(fees,jdbc,actor,admin,tx);
                concurrency(fees,actor);
                if(!databaseOnly) real(context.getBean(ModelGateway.class),context.getBean(ModelRegistry.class),fees,jdbc,actor);
            } finally {
                // 不按测试前缀模糊删除，更不清库；仅本入口刚创建的两个用户及自建任务／费用。
                tx.executeWithoutResult(s->{for(var user:List.of(actor,admin)) {
                    jdbc.update("DELETE a FROM fee_attempts a JOIN fee_scopes f ON f.scope_id=a.scope_id WHERE f.actor_user_id=?",user.userId());
                    jdbc.update("DELETE FROM fee_scopes WHERE actor_user_id=?",user.userId());
                    jdbc.update("DELETE FROM ai_runs WHERE actor_user_id=?",user.userId());
                    jdbc.update("DELETE FROM ai_tasks WHERE requester_user_id=?",user.userId());
                    jdbc.update("DELETE FROM auth_tokens WHERE user_id=?",user.userId());
                    jdbc.update("DELETE FROM users WHERE id=?",user.userId());
                }});
                check(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (?,?)",Integer.class,actor.userId(),admin.userId())==0,"own_fixture_users_removed");
                check(jdbc.queryForObject("SELECT COUNT(*) FROM fee_scopes WHERE actor_user_id IN (?,?)",Integer.class,actor.userId(),admin.userId())==0,"own_fixture_fees_removed");
            }
        }
        var evidence=new LinkedHashMap<String,Object>();evidence.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256"));evidence.put("checks",CHECKS);evidence.put("passed",CHECKS.size());evidence.put("real_model_summaries",REAL);
        evidence.put("not_verified",List.of("provider price or final invoice reconciliation","real provider failure automatic fallback","HTTP plus real provider plus SQL full chain","OS kill or multi-process recovery","real ES batch cost chain","media approvals and callbacks are future stages"));
        Files.createDirectories(Path.of("var/stage-S07"));Files.writeString(Path.of("var/stage-S07/"+(databaseOnly?"database-native-results.json":"native-results.json")),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println(JSON.writeValueAsString(Map.of("passed",CHECKS.size(),"real_model_runs",REAL.size(),"own_fixtures_removed",true)));
    }

    /** 用合成固定单价验证真实SQL计算／状态，不当真实市场价格或账单。 */
    private static void database(FeeStorePort fees,JdbcTemplate jdbc,UserContext actor,UserContext admin,TransactionTemplate tx) {
        var p=price("v1","1","2");var scope=scope(actor,"RUN",UUID.randomUUID().toString());
        var key=reserve(fees,scope,p,"1",100,100,false);
        check(fees.summary(actor,"RUN",scope.runId()).pendingAttempts()==1,"mysql_reserved_before_send");
        denied(()->fees.summary(admin,"RUN",scope.runId()),"mysql_other_admin_denied");
        denied(()->fees.aggregate(actor,Instant.now().minusSeconds(60)),"mysql_user_aggregate_denied");
        fees.sending(key);fees.complete(key,7,3,"SUCCESS");fees.complete(key,7,3,"SUCCESS");
        var sum=fees.summary(actor,"RUN",scope.runId());
        check(sum.estimatedAmount().compareTo(new BigDecimal("0.00001300"))==0 && sum.settledAttempts()==1,"mysql_exact_duplicate_settlement_once");
        conflict(()->fees.complete(key,8,3,"SUCCESS"),"mysql_conflicting_response_rejected");
        fees.release(key);check(fees.summary(actor,"RUN",scope.runId()).estimatedAmount().equals(sum.estimatedAmount()),"mysql_cancel_not_refund");
        var p2=price("v2","5","6");var k2=reserve(fees,scope,p2,"9",10,10,false);fees.sending(k2);fees.complete(k2,7,3,"SUCCESS");
        check(jdbc.queryForObject("SELECT price_version FROM fee_attempts WHERE operation_id=?",String.class,key.operationId()).equals("v1"),"mysql_price_history_immutable");
        check(fees.summary(actor,"RUN",scope.runId()).limitAmount().compareTo(BigDecimal.ONE)==0,"mysql_scope_limit_not_reset");
        jdbc.update("INSERT INTO ai_runs(trace_id,actor_user_id,status,model_id,attempts,mock,created_at) VALUES(?,?,'SUCCESS','synthetic',2,FALSE,CURRENT_TIMESTAMP(6))",scope.runId(),actor.userId());
        jdbc.update("DELETE FROM ai_runs WHERE trace_id=? AND actor_user_id=?",scope.runId(),actor.userId());
        check(fees.summary(actor,"RUN",scope.runId()).settledAttempts()==2,"mysql_trace_delete_preserves_ledger");
        var unknown=reserve(fees,scope,p,"1",20,20,false);fees.sending(unknown);fees.complete(unknown,null,null,"MODEL_TIMEOUT");
        var unknownSum=fees.summary(actor,"RUN",scope.runId());
        check(unknownSum.unknownAttempts()==1 && unknownSum.reservedAmount().signum()>0 && unknownSum.costStatus().equals("UNKNOWN"),"mysql_unknown_holds_reservation");
        fees.release(unknown);check(fees.summary(actor,"RUN",scope.runId()).unknownAttempts()==1,"mysql_unknown_cannot_release");
        fees.complete(unknown,5,2,"MODEL_TIMEOUT");check(fees.summary(actor,"RUN",scope.runId()).unknownAttempts()==0,"mysql_late_usage_reconciles_same_key");
        String old=UUID.randomUUID().toString();var crashed=reserve(fees,scope(actor,"RUN",old),p,"1",30,30,false);fees.sending(crashed);
        jdbc.update("UPDATE fee_attempts SET created_at=? WHERE operation_id=?",java.sql.Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")),crashed.operationId());
        // 截止时间确保不会接触现有用户当前或历史账本，先证明只有本夹具符合。
        Instant cutoff=Instant.parse("2000-01-02T00:00:00Z");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE created_at<? AND state IN ('RESERVED','SENDING')",Integer.class,java.sql.Timestamp.from(cutoff))==1,"mysql_reconcile_fixture_isolated");
        check(fees.markUnknownBefore(cutoff,1)==1 && fees.summary(actor,"RUN",old).unknownAttempts()==1,"mysql_crash_window_bounded_unknown");
        var restarted=new com.example.ailab.data.repository.FeeRepository(new SqlSupport(jdbc));
        check(restarted.summary(actor,"RUN",old).reservedAmount().signum()>0,"mysql_repository_restart_preserves_reservation");
        fees.complete(crashed,3,2,"SUCCESS");
        var noPrice=reserve(fees,scope(actor,"RUN",UUID.randomUUID().toString()),null,"1",100,100,false);fees.sending(noPrice);fees.complete(noPrice,7,3,"SUCCESS");
        check(jdbc.queryForObject("SELECT estimated_amount IS NULL AND state='UNKNOWN' FROM fee_attempts WHERE operation_id=?",Boolean.class,noPrice.operationId()),"mysql_provider_usage_without_price_is_unknown");
        var unsent=reserve(fees,scope,p,"1",10,10,false);
        fees.release(new FeeReservation(unsent.operationId(),noPrice.scopeId()));
        check(jdbc.queryForObject("SELECT state FROM fee_attempts WHERE operation_id=?",String.class,unsent.operationId()).equals("RESERVED"),"mysql_release_requires_matching_scope");
        fees.release(unsent);
        check(jdbc.queryForObject("SELECT state FROM fee_attempts WHERE operation_id=?",String.class,unsent.operationId()).equals("RELEASED"),"mysql_proven_unsent_release");
        var simulated=reserve(fees,scope(actor,"RUN",UUID.randomUUID().toString()),p,"1",10,10,true);fees.sending(simulated);fees.complete(simulated,7,3,"SUCCESS");
        check(jdbc.queryForObject("SELECT estimated_amount IS NULL AND state='SIMULATED' FROM fee_attempts WHERE operation_id=?",Boolean.class,simulated.operationId()),"mysql_mock_excluded_from_real_money");
        var overScope=scope(actor,"RUN",UUID.randomUUID().toString());var over=reserve(fees,overScope,price("over","1000000","1000000"),"3",1,1,false);fees.sending(over);fees.complete(over,2,2,"SUCCESS");
        check(fees.summary(actor,"RUN",overScope.runId()).overLimit(),"mysql_actual_overrun_preserved");
        budgetExceeded(()->reserve(fees,overScope,p,"30",1,1,false),"mysql_overrun_blocks_future_reserve");
        // 旧任务仅保存历史缺账计数，不猜历史价；新任务恢复后仍在相同费用scope内累计。
        long task=new SqlSupport(jdbc).insert("INSERT INTO ai_tasks(requester_user_id,task_type,request_json,request_hash,status,model_attempts) VALUES(?,'RESEARCH_REPORT','{}',?,'PAUSED',2)",actor.userId(),"0".repeat(64));
        var taskScope=scope(actor,"TASK",Long.toString(task));var taskFee=reserve(fees,taskScope,p,"1",10,10,false);fees.sending(taskFee);fees.complete(taskFee,7,3,"SUCCESS");
        check(fees.summary(actor,"TASK",Long.toString(task)).legacyUntrackedAttempts()==2,"mysql_legacy_task_not_free");
        var recovered=scope(actor,"TASK",Long.toString(task));var taskFee2=reserve(fees,recovered,p,"99",10,10,false);fees.sending(taskFee2);fees.complete(taskFee2,7,3,"SUCCESS");
        check(fees.summary(actor,"TASK",Long.toString(task)).settledAttempts()==2 && fees.summary(actor,"TASK",Long.toString(task)).limitAmount().compareTo(BigDecimal.ONE)==0,"mysql_task_recovery_same_scope");
        var tokenScope=scope(actor,"RUN",UUID.randomUUID().toString());
        var tokenKey=reserve(fees,tokenScope,null,"1",6000,0,false);fees.sending(tokenKey);fees.complete(tokenKey,null,0,"MODEL_TIMEOUT");
        budgetExceeded(()->reserve(fees,tokenScope,null,"99",5000,0,false),"mysql_unknown_price_still_enforces_tokens");
        String legacyRun=UUID.randomUUID().toString();jdbc.update("INSERT INTO ai_runs(trace_id,actor_user_id,status,model_id,attempts,mock,created_at) VALUES(?,?,'FAILED','synthetic',2,FALSE,CURRENT_TIMESTAMP(6))",legacyRun,actor.userId());
        check(fees.summary(actor,"RUN",legacyRun).legacyUntrackedAttempts()==2 && fees.summary(actor,"RUN",legacyRun).costStatus().equals("UNKNOWN"),"mysql_legacy_run_unknown_not_free");
        // 入库两批和新run共享同一代次；整个合成结构及费用事务回滚，不进入队列。
        tx.executeWithoutResult(status->{try {
            var sql=new SqlSupport(jdbc);long base=sql.insert("INSERT INTO knowledge_bases(owner_user_id,name) VALUES(?,'S07合成费用')",actor.userId());
            long doc=sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,'合成','txt')",base,actor.userId());
            jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,1,'合成',?)",doc,"0".repeat(64));
            long ingestion=sql.insert("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status) VALUES(?,1,1,?,'FAILED')",doc,actor.userId());
            for(int ordinal=0;ordinal<2;ordinal++) {
                var batchScope=scope(actor,"INGESTION",Long.toString(ingestion));
                var batch=fees.reserve(batchScope,UUID.randomUUID().toString(),"synthetic","EMBEDDING",32,0,p,"CNY",BigDecimal.ONE,10000,false);
                fees.sending(batch);fees.complete(batch,9,0,"SUCCESS");
            }
            var ingestionSum=fees.summary(actor,"INGESTION",Long.toString(ingestion));
            check(ingestionSum.settledAttempts()==2 && ingestionSum.providerInputTokens()==18,"mysql_ingestion_batches_same_scope");
            denied(()->fees.summary(admin,"INGESTION",Long.toString(ingestion)),"mysql_other_admin_ingestion_fee_denied");
        } finally {status.setRollbackOnly();}});
        check(fees.aggregate(admin,Instant.now().minusSeconds(3600)).stream().allMatch(a->a.currency().equals("CNY")),"mysql_admin_low_cardinality_currency");
        // 回滚预留不遗留半行scope，事务代理生效；不把该探针称网络集成。
        String rolled=UUID.randomUUID().toString();tx.executeWithoutResult(status->{reserve(fees,scope(actor,"RUN",rolled),p,"1",1,1,false);status.setRollbackOnly();});
        check(jdbc.queryForObject("SELECT COUNT(*) FROM fee_attempts WHERE run_id=?",Integer.class,rolled)==0,"mysql_reservation_atomic_rollback");
    }

    /** 两个真实连接并发争夺仅容纳一次的金额，不借内存锁证明数据库原子性。 */
    private static void concurrency(FeeStorePort fees,UserContext actor) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);var scope=scope(actor,"RUN",UUID.randomUUID().toString());
        try {
            var futures=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++)futures.add(pool.submit(()->{gate.await();try{reserve(fees,scope,price("concurrent","1000000","1000000"),"1",1,0,false);return true;}catch(LabException e){if(!e.code().equals("BUDGET_EXCEEDED"))throw e;return false;}}));
            gate.countDown();int won=0;for(var result:futures)if(result.get(15,TimeUnit.SECONDS))won++;
            check(won==1 && fees.summary(actor,"RUN",scope.runId()).reservedAmount().compareTo(BigDecimal.ONE)==0,"mysql_concurrent_reserve_never_exceeds_limit");
        } finally {pool.shutdownNow();}
    }

    /** 每个聊天目标一次和小向量一次；生产账本持久保存真实usage，但没有价格不声明实金额。 */
    private static void real(ModelGateway gateway,ModelRegistry registry,FeeStorePort fees,JdbcTemplate jdbc,UserContext actor) {
        if(registry.mock())throw new IllegalStateException("真实专项要求real模式");
        for(String model:registry.candidates("KNOWLEDGE_QA",Set.of("CHAT")).stream().limit(2).toList()) {
            var scope=scope(actor,"RUN",UUID.randomUUID().toString());var budget=new ExecutionBudget(Duration.ofSeconds(60),2).fees(scope);
            var turn=gateway.chat("KNOWLEDGE_QA",ModelRegistry.Selection.exact(model),ModelInput.fixed("只回复一个简短事实句。",List.of(),"合成验收：2加3等于几？"),budget);
            var sum=fees.summary(actor,"RUN",scope.runId());REAL.add(sum);
            check(!turn.mock() && sum.attempts()==1 && sum.providerInputTokens()>0 && sum.providerOutputTokens()>0,"real_"+model+"_usage_durable");
            check(sum.costStatus().equals("UNKNOWN") && sum.unknownAttempts()==1,"real_"+model+"_missing_price_not_free");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM ai_runs WHERE trace_id=?",Integer.class,scope.runId())==0 && sum.attempts()==1,"real_"+model+"_ledger_independent_of_spans");
        }
        var scope=scope(actor,"RUN",UUID.randomUUID().toString());var vector=gateway.embed(List.of("S07合成费用验证。"),new ExecutionBudget(Duration.ofSeconds(60),1).fees(scope));
        var sum=fees.summary(actor,"RUN",scope.runId());REAL.add(sum);
        check(!vector.mock() && vector.inputTokens()!=null && sum.providerInputTokens()==vector.inputTokens(),"real_embedding_provider_usage_durable");
        check(sum.unknownAttempts()==1 && sum.estimatedAmount().signum()==0,"real_embedding_amount_unknown_not_invoice");
    }
    /** 独立用户只由本专项创建，既有用户凭证不读取、不改动。 */
    private static UserContext actor(SqlSupport sql,String role) {long id=sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,?,FALSE)","s07-native-"+UUID.randomUUID(),"synthetic-non-login",role);return new UserContext(id,UserContext.Role.valueOf(role),true,1,false);}
    /** 服务端稳定后台资源与独立执行run，模拟数据不带正文。 */
    private static FeeScope scope(UserContext actor,String kind,String resource) {return new FeeScope(actor,kind,resource,kind.equals("RUN")?resource:UUID.randomUUID().toString());}
    /** 合成定价只验证程序计算，不冒称提供方价格。 */
    private static FeePrice price(String version,String input,String output) {return new FeePrice("synthetic",version,"CNY","PER_MILLION_TOKENS",Instant.parse("2026-01-01T00:00:00Z"),new BigDecimal(input),new BigDecimal(output));}
    /** 每次预留均为独立稳定尝试键，远程重试的费用绝不覆盖上一行。 */
    private static FeeReservation reserve(FeeStorePort fees,FeeScope scope,FeePrice price,String limit,long in,long out,boolean mock) {return fees.reserve(scope,UUID.randomUUID().toString(),"synthetic","CHAT",in,out,price,"CNY",new BigDecimal(limit),10000,mock);}
    /** 权限失败须明确403业务码，而非任意SQL异常。 */
    private static void denied(Runnable action,String name) {expect(action,"ACCESS_DENIED",name);}
    /** 重复响应冲突须明确拒绝，不能覆写历史。 */
    private static void conflict(Runnable action,String name) {expect(action,"FEE_CONFLICT",name);}
    /** 预算耗尽不是外部临时故障，不自动购买。 */
    private static void budgetExceeded(Runnable action,String name) {expect(action,"BUDGET_EXCEEDED",name);}
    /** 只认可指定稳定错误，失败不能计通过。 */
    private static void expect(Runnable action,String code,String name) {try{action.run();throw new AssertionError(name);}catch(LabException e){check(e.code().equals(code),name);}}
    /** 程序断言立即失败，证据仅保存已通过项和脱敏摘要。 */
    private static void check(boolean value,String name) {if(!value)throw new AssertionError(name);CHECKS.add(name);}
}
