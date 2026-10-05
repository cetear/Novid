package com.example.ailab.app;

import com.example.ailab.ai.model.*;
import com.example.ailab.ai.tools.*;
import com.example.ailab.ai.orchestration.BoundedToolLoop;
import com.example.ailab.ai.orchestration.worker.ReportTaskWorker;
import com.example.ailab.ai.orchestration.planner.PlanValidator;
import com.example.ailab.contract.context.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.business.application.AccountApplicationService;
import com.example.ailab.data.repository.SqlSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** S06分层专项：真实模型＋合成知识／任务端口；真实MySQL短事务回滚，绝不扫描正式队列。 */
public final class S06NativeValidation {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final List<String> CHECKS=new ArrayList<>();
    private static final List<List<TraceNode>> MODEL_GRAPHS=new ArrayList<>();
    private static final String PASSWORD="s06-synthetic-validation-password";
    /** 模型远程调用全部在数据库事务外；数据库仅回滚保存合成节点，没有SQL＋模型HTTP全链路声明。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        if(args.length==0 || !args[0].equals("--database-only")) {
            models();
            Files.createDirectories(Path.of("var/stage-S06"));
            // 模型费用事实和图先保存，后续SQL故障不能抹去已经执行的分层证据。
            Files.writeString(Path.of("var/stage-S06/model-native-results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("application_jar_sha256",System.getProperty("validation.application-jar-sha256"),"checks",List.copyOf(CHECKS),"model_graphs",MODEL_GRAPHS)));
        }
        database();
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("application_jar_sha256",System.getProperty("validation.application-jar-sha256"));
        evidence.put("checks",CHECKS);evidence.put("passed",CHECKS.size());evidence.put("model_graphs",MODEL_GRAPHS);
        evidence.put("not_verified",List.of("SQL plus real model plus HTTP full chain","OS kill recovery","real provider automatic failover","full profile quality","external Langfuse","manual browser"));
        Files.createDirectories(Path.of("var/stage-S06"));
        Files.writeString(Path.of("var/stage-S06/"+(MODEL_GRAPHS.isEmpty()?"database-native-results.json":"native-results.json")),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence));
        System.out.println(JSON.writeValueAsString(Map.of("passed",CHECKS.size(),"model_runs",MODEL_GRAPHS.size(),"fixtures_rolled_back",true)));
    }
    /** 现有配置只读，小输出额度降低调用成本，SDK和路由仍采用正式实现。 */
    private static void models() throws Exception {
        var env=new StandardEnvironment();env.getPropertySources().addLast(new MapPropertySource("local-env",LocalEnvironmentLoader.read(Path.of(".env"))));
        for(var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml"))) env.getPropertySources().addLast(source);
        var original=Binder.get(env).bind("lab.model",Bindable.of(ModelProperties.class)).get();
        if(!original.mode().equals("real")) throw new IllegalStateException("真实专项要求real模式");
        var defs=new LinkedHashMap<String,ModelProperties.Definition>();
        original.models().forEach((id,d)->defs.put(id,new ModelProperties.Definition(d.providerId(),d.endpoint(),d.modelName(),d.credentialRef(),d.enabled(),d.capabilities(),d.qualityTags(),d.dataClassifications(),d.contextWindow(),Math.min(d.outputLimit(),512),d.dimensions(),d.timeoutSeconds(),d.quotaGroup(),d.priceRef(),d.maxConcurrency())));
        var registry=new ModelRegistry(new ModelProperties(original.mode(),defs,original.profiles(),original.routing(),original.externalDataAllowed(),original.failover()),env);
        var gateway=new ModelGateway(registry);var actor=new UserContext(1,UserContext.Role.USER,true,1,false);
        var knowledge=knowledge();
        try(var tools=new ToolExecutionService(knowledge,null)) {
            for(String target:registry.candidates("KNOWLEDGE_QA",Set.of("CHAT","TOOLS")).stream().limit(2).toList()) {
                var captured=new AtomicReference<List<TraceNode>>();var trace=new TraceContext(2000,(nodes,incomplete)->captured.set(nodes));
                var budget=new ExecutionBudget(Duration.ofSeconds(60),4);
                try(var root=trace.span("REQUEST","synthetic_tool")) {
                    budget.traced(root.context());
                    var result=new BoundedToolLoop(gateway,tools).run(actor,ScopeRequest.self(),ModelRegistry.Selection.exact(target),
                            ModelInput.fixed("统计只根据工具返回。",List.of(),"必须调用get_knowledge_statistics，只回答总数、待处理、就绪三个数字。"),budget,()->{});
                    check(!result.turn().mock() && !result.exchanges().isEmpty(),target+"_real_tool_pair");
                }
                trace.finish();var nodes=captured.get();MODEL_GRAPHS.add(nodes);
                check(nodes.stream().filter(n->n.type().equals("MODEL")).count()==budget.attempts(),target+"_every_attempt_recorded");
                check(nodes.stream().anyMatch(n->n.type().equals("TOOL") && n.toolCallHash()!=null && n.status().equals("SUCCESS")),target+"_tool_duration_and_hash");
                check(nodes.stream().filter(n->n.type().equals("MODEL")).allMatch(n->n.usageSource().equals("PROVIDER") || n.usageSource().equals("UNKNOWN")),target+"_real_usage_not_zero_filled");
            }
        }
        // 正式ReportTaskWorker真线程与真实模型；任务端口是合成夹具，故单列，不冒称真实持久调度。
        var request=new TaskRequest("RESEARCH_REPORT","独立研究合成文档与统计，两角色互不依赖，各写一句话，报告保留引用。",ScopeRequest.self(),List.of(10L),"s06-native","FIXED");
        var lease=new TaskLease(new TaskSnapshot(1,1,"RESEARCH_REPORT","RUNNING",1,0,0,null,null),request,actor,"synthetic",1);
        var saved=new CopyOnWriteArrayList<TaskCheckpoint>(); var published=new CompletableFuture<Void>();var graph=new CompletableFuture<List<TraceNode>>();var claimed=new AtomicBoolean();
        var tasks=(TaskStorePort)java.lang.reflect.Proxy.newProxyInstance(TaskStorePort.class.getClassLoader(),new Class<?>[]{TaskStorePort.class},(proxy,method,arguments)->{
            switch(method.getName()) {
                case "claim": return claimed.compareAndSet(false,true)?Optional.of(lease):Optional.empty();
                case "renew": return true;
                case "checkpoints": return List.copyOf(saved);
                case "checkpoint": saved.add((TaskCheckpoint)arguments[1]);return null;
                case "publish": published.complete(null);return null;
                case "fail": published.completeExceptionally(new LabException((String)arguments[1],"合成任务失败"));return null;
                case "plan": return Optional.empty();
                default: if(method.getReturnType()==void.class)return null; throw new IllegalStateException("未声明夹具方法");
            }
        });
        var worker=new ReportTaskWorker(tasks,knowledge,gateway,new PlanValidator());
        try {
            worker.tracing((id,user,session,task,ingestion)->new TraceContext(2000,(nodes,incomplete)->graph.complete(nodes)));
            worker.scan();published.get(120,TimeUnit.SECONDS);var nodes=graph.get(5,TimeUnit.SECONDS);MODEL_GRAPHS.add(nodes);
            var research=role(nodes,"ResearchWorker");var analysis=role(nodes,"AnalysisWorker");var report=role(nodes,"ReportWriter");
            check(research.startedAt().isBefore(analysis.endedAt()) && analysis.startedAt().isBefore(research.endedAt()),"real_worker_parallel_overlap");
            check(report.dependsOn().containsAll(List.of(research.spanId(),analysis.spanId())) && !report.startedAt().isBefore(research.endedAt()) && !report.startedAt().isBefore(analysis.endedAt()),"real_worker_actual_join");
            check(nodes.stream().filter(n->n.type().equals("MODEL")).count()==3,"real_worker_three_model_leaves");
            check(nodes.stream().filter(n->n.type().equals("MODEL")).allMatch(n->n.agentId()!=null && n.modelId()!=null && n.profile()!=null),"real_worker_agent_route_association");
        } finally { worker.close(); }
    }
    /** 固定合成知识事实，不使用现有资料、ES或原文文件。 */
    private static KnowledgeCapabilityPort knowledge() {
        return new KnowledgeCapabilityPort() {
            /** 合成身份范围不声明SQL授权证据。 */
            public AuthorizedKnowledgeScope authorize(UserContext actor,ScopeRequest scope) {return null;}
            /** 两角色只读同一短合成资料，来源标签由程序生成。 */
            public DocumentContent document(UserContext actor,ScopeRequest scope,long id) {return new DocumentContent(new DocumentSnapshot(10,1,1,"合成文档","txt",1,"READY",1L),"合成资料：持久追踪保存执行事实，观测失败不能改变预算。",List.of());}
            /** 真实工具执行得到固定统计，用于核对消息配对。 */
            public KnowledgeStatistics statistics(UserContext actor,ScopeRequest scope) {return new KnowledgeStatistics(7,2,5);}
        };
    }
    /** V9正式迁移并验证真实MySQL，不领取任务；所有合成用户与观测在一个短事务回滚。 */
    private static void database() throws Exception {
        var app=new SpringApplication(LabApplication.class);LocalEnvironmentLoader.initialize(app,Path.of(".env"));
        try(var context=app.run("--lab.search.enabled=false","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false","--lab.ingestion.worker-enabled=false","--lab.model.mode=mock","--lab.observability.export-enabled=false","--spring.main.web-application-type=none","--spring.main.banner-mode=off","--logging.level.root=OFF")) {
            var jdbc=context.getBean(JdbcTemplate.class);var sql=ValidationSql.from(context);var store=context.getBean(TraceRecordPort.class);
            var before=counts(jdbc);var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status->{try {
                var owner=actor(context.getBean(AccountApplicationService.class),sql);var other=actor(context.getBean(AccountApplicationService.class),sql);
                var nodes=new AtomicReference<List<TraceNode>>();var trace=new TraceContext(2000,(n,i)->nodes.set(n));
                try(var root=trace.span("REQUEST","synthetic")) {try(var child=root.context().span("MODEL","chat")){child.model("synthetic","QA","qa","s06","ORDERED",1,7,3,"SIMULATED");}}
                trace.finish();String id=UUID.randomUUID().toString();Instant now=Instant.now();
                var run=new TraceSnapshot(id,owner.userId(),"SUCCESS","synthetic",1,true,now,now,null,999L,null,null,false,false,nodes.get().size());
                store.recordGraph(run,nodes.get());store.recordGraph(run,nodes.get());
                check(store.graph(owner,id).nodes().size()==2,"mysql_graph_idempotent");
                check(!store.graph(owner,id).incomplete(),"mysql_complete_graph");
                check(store.graph(owner,id).edges().size()==1,"mysql_actual_parent_edge");
                check(store.list(other,0,20).isEmpty(),"mysql_other_admin_list_empty");
                denied(()->store.read(other,id),"mysql_other_admin_detail_denied");denied(()->store.graph(other,id),"mysql_other_admin_graph_denied");
                check(id.equals(store.previous(owner.userId(),999L,null)),"mysql_recovery_previous_same_owner");
                check(store.previous(other.userId(),999L,null)==null,"mysql_recovery_owner_filtered");
                String resumed=UUID.randomUUID().toString();var next=new TraceSnapshot(resumed,owner.userId(),"SUCCESS","synthetic",0,true,now.plusSeconds(1),now.plusSeconds(1),null,999L,null,id,false,false,0);
                store.record(next);check(store.read(owner,resumed).previousTraceId().equals(id),"mysql_new_execution_links_previous");
                store.delivered(owner,id,now);
                var firstDelivered=store.read(owner,id).firstDeliverableAt();
                // MySQL可能按微秒舍入纳秒输入，核验首次事实不被第二次交付覆盖，不假定截断规则。
                store.delivered(owner,id,now.plusSeconds(1));check(store.read(owner,id).firstDeliverableAt().equals(firstDelivered),"mysql_first_delivery_not_overwritten");
                String partial=UUID.randomUUID().toString();store.record(new TraceSnapshot(partial,owner.userId(),"RUNNING","none",0,false,now));
                check(store.graph(owner,partial).incomplete(),"mysql_running_or_crashed_is_incomplete");
                // 仅清理本事务生成的旧运行，before在既有记录之前，绝不清理真实历史。
                String old=UUID.randomUUID().toString();
                var cutoff=Instant.parse("2000-01-02T00:00:00Z");
                check(jdbc.queryForObject("SELECT COUNT(*) FROM ai_runs WHERE created_at<?",Long.class,java.sql.Timestamp.from(cutoff))==0,"mysql_retention_fixture_isolated");
                store.recordGraph(new TraceSnapshot(old,owner.userId(),"SUCCESS","none",0,true,Instant.parse("2000-01-01T00:00:00Z"),now,null,null,null,null,false,false,2),nodes.get());
                // 明确核对截止前只有本夹具，真实保留清理最多删除这一行，并在本事务回滚。
                check(store.purge(cutoff,1)==1,"mysql_retention_bounded_purge");
                check(jdbc.queryForObject("SELECT COUNT(*) FROM ai_spans WHERE trace_id=?",Long.class,old)==0,"mysql_span_delete_cascade");
            } finally {status.setRollbackOnly();}});
            check(counts(jdbc).equals(before),"mysql_all_fixture_rows_rolled_back");
        }
    }
    /** 只创建唯一前缀合成管理员，密码不进入证据，禁止读已有用户。 */
    private static UserContext actor(AccountApplicationService accounts,ValidationSql sql) {
        String name="s06-test-"+UUID.randomUUID().toString().substring(0,12);
        sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,'ADMIN',FALSE)",name,new BCryptPasswordEncoder(12).encode(PASSWORD));
        return accounts.authenticate(accounts.login(name,PASSWORD).token());
    }
    /** 仅读行数用于回滚核验，不读取用户正文。 */
    private static Map<String,Long> counts(JdbcTemplate jdbc) {
        var result=new LinkedHashMap<String,Long>();for(String table:List.of("users","auth_tokens","ai_runs","ai_spans","ai_tasks","documents","artifacts","sessions","messages"))result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return result;
    }
    /** 从实际节点定位角色，不能以静态计划代替。 */
    private static TraceNode role(List<TraceNode> nodes,String role) {return nodes.stream().filter(n->role.equals(n.agentId()) && n.type().equals("AGENT")).findFirst().orElseThrow();}
    /** 失败路径必须匹配稳定拒绝码。 */
    private static void denied(Runnable action,String name) {try {action.run();throw new AssertionError(name);}catch(LabException e){check(e.code().equals("ACCESS_DENIED"),name);}}
    /** 程序精确断言，失败退出不能记通过。 */
    private static void check(boolean condition,String name) {if(!condition)throw new AssertionError(name);CHECKS.add(name);}
}
