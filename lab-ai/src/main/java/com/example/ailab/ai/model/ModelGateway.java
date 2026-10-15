package com.example.ailab.ai.model;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.fees.FeeAccounting;

import com.example.ailab.contract.error.LabException;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import dev.langchain4j.model.output.FinishReason;
import com.example.ailab.contract.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.*;

import java.time.Clock;

/**
 * 所有 chat 与 embedding 唯一入口；SDK 自动重试明确关闭。
 */
@Component
public class ModelGateway {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ModelGateway.class);

    public record Turn(String text, String modelId, Integer inputTokens, Integer outputTokens, boolean mock,
                       String finishReason, AiMessage rawMessage, ModelRoute route, List<EvidenceBundle> evidence) {
        /**
         * 旧六参数构造仍可用于测试，不伪造路由和用量事实。
         */
        public Turn(String text, String modelId, Integer inputTokens, Integer outputTokens, boolean mock, String finishReason) {
            this(text, modelId, inputTokens, outputTokens, mock, finishReason, null, null, List.of());
        }

        /**
         * Mock 调用兼容构造，真实结果使用 SDK 提供的结束原因。
         */
        public Turn(String text, String modelId, Integer inputTokens, Integer outputTokens, boolean mock) {
            this(text, modelId, inputTokens, outputTokens, mock, "STOP");
        }
    }

    public record Vectors(List<List<Float>> vectors, String modelVersion, boolean mock, Integer inputTokens) {
        /**
         * 旧模拟构造兼容，未知用量必须为空。
         */
        public Vectors(List<List<Float>> vectors, String modelVersion, boolean mock) {
            this(vectors, modelVersion, mock, null);
        }
    }

    private final ModelRegistry registry;
    private final ModelHealthTracker health;
    private final DeadlineHttpClient transport = new DeadlineHttpClient();
    private final ConcurrentHashMap<String, dev.langchain4j.model.chat.ChatModel> chatClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpenAiEmbeddingModel> embeddingClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Semaphore> targetConcurrency = new ConcurrentHashMap<>();
    private final Semaphore concurrency = new Semaphore(ModelProperties.MAX_CONCURRENCY, true);
    private static final ObjectMapper JSON = new ObjectMapper();
    private FeeAccounting fees;

    /**
     * 正式Spring装配强制可靠账本；旧显式协议构造未装配时仅用于分层测试。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void accounting(FeeAccounting fees) {
        this.fees = fees;
    }

    /**
     * 只通过受控 Registry 获取实际目标。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ModelGateway(ModelRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    /**
     * 固定时钟只供确定性健康测试，不启动后台探测。
     */
    public ModelGateway(ModelRegistry registry, Clock clock) {
        this.registry = registry;
        this.health = new ModelHealthTracker(registry.configuration().failover(), clock);
    }

    /**
     * 最多三个尝试、一次主备切换；写工具不在本方法中执行或重放。
     */
    public Turn chat(String task, String system, String prompt, ExecutionBudget budget) {
        return chat(task, system, List.of(), prompt, budget);
    }

    /**
     * 历史以 SDK 消息进入同一模型入口；摘要、主备与回答共享调用方的预算。
     */
    public Turn chat(String task, String system, List<ChatMessage> history, String prompt, ExecutionBudget budget) {
        return chat(task, ModelRegistry.Selection.auto(), ModelInput.fixed(system, history, prompt), budget);
    }

    /**
     * 显式受控选择与目标输入回调，写工具仍不在本层执行。
     */
    public Turn chat(String task, ModelRegistry.Selection selection, ModelInput input, ExecutionBudget budget) {
        return generate(task, selection, input, budget, null, "", new ArrayList<>(), null, List.of());
    }

    /** 只核验注册路由及完整消息容量；不取得执行权、不调用远程服务、不消费额度。 */
    public boolean fitsStructured(String task,ModelInput input,StructuredSchema<?> schema) {
        var route=registry.route(task,ModelRegistry.Selection.auto(),Set.of("CHAT","STRUCTURED_OUTPUT"));
        for(String id:route.ids()) {
            var definition=effectiveDefinition(input,registry.definition(id));if(input.requiredOutputTokens()>definition.outputLimit())continue;
            try {
                var prepared=input.prepare(withReservedSchema(definition,schema,""));
                var messages=new ArrayList<>(prepared.messages());messages.add(SystemMessage.from(schema.instruction(referenceIds(prepared.evidence()))));
                if(ModelInput.count(messages)+definition.outputLimit()<=definition.contextWindow())return true;
            }catch(com.example.ailab.contract.error.LabException error){if(!error.code().equals("MODEL_CONTEXT_INSUFFICIENT"))throw error;}
        }
        return false;
    }

    /**
     * 手写工具循环入口；续轮固定已产生申请的目标，禁止跨提供方搬运协议或重放工具。
     */
    public Turn toolTurn(String task, ModelRegistry.Selection selection, ModelInput input, ExecutionBudget budget,
                         List<dev.langchain4j.agent.tool.ToolSpecification> tools, String pinnedModel) {
        if (tools.isEmpty()) throw new LabException("TOOL_DISABLED", "没有可暴露工具");
        return generate(task, selection, input, budget, null, "", new ArrayList<>(), pinnedModel, tools);
    }

    /**
     * 工具循环最后一轮只汇总已配对结果；固定原目标且不再暴露工具，不另开预算。
     */
    public Turn finishToolTurn(String task, ModelRegistry.Selection selection, ModelInput input,
                               ExecutionBudget budget, String pinnedModel) {
        return generate(task, selection, input, budget, null, "", new ArrayList<>(), pinnedModel, List.of());
    }

    /**
     * 摘要／报告每次重试和备用前重核来源；固定整页语义不允许静默裁剪。
     */
    public Turn chatVerified(String task, String system, String prompt, ExecutionBudget budget, Runnable verify) {
        var fixed = ModelInput.fixed(system, List.of(), prompt);
        return chat(task, ModelRegistry.Selection.auto(), target -> {
            verify.run();
            return fixed.prepare(target);
        }, budget);
    }

    /** 一次纠正共享原预算；资料摘要支持局部替换，其余错误沿用完整结构修复。 */
    public <T> StructuredTurn<T> structured(String task, ModelRegistry.Selection selection, ModelInput input,
                                            ExecutionBudget budget, StructuredSchema<T> schema) {
        return structured(task,selection,input,budget,schema,null,true);
    }

    private com.example.ailab.contract.port.ModelCallArchivePort callArchive;
    @org.springframework.beans.factory.annotation.Value("${lab.model-call-archive.retention-days:7}")
    private int archiveRetention=7;
    private final ThreadLocal<CallExecution> callExecution=new ThreadLocal<>();
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void callArchive(com.example.ailab.contract.port.ModelCallArchivePort archive) { callArchive=archive; }
    private static final class CallExecution {
        final com.example.ailab.contract.dto.ModelCalls.Context context;
        com.example.ailab.contract.dto.ModelCalls.Context attemptContext;
        PendingCall pending;
        CallExecution(com.example.ailab.contract.dto.ModelCalls.Context context) { this.context=context;this.attemptContext=context; }
    }
    private static final class PendingCall {
        final String id=java.util.UUID.randomUUID().toString();
        final java.time.Instant started=java.time.Instant.now();
        final DeadlineHttpClient.Capture capture=new DeadlineHttpClient.Capture();
        final com.example.ailab.contract.context.TraceContext.Span span;
        final String parent,model;final int attempt;
        final com.example.ailab.contract.dto.ModelCalls.Context context;
        String outcome="RUNNING",validation="SKIPPED",error;
        long validationMillis;
        com.example.ailab.contract.dto.ValidationDiagnostic diagnostic;
        PendingCall(com.example.ailab.contract.context.TraceContext.Span span,String parent,String model,int attempt,com.example.ailab.contract.dto.ModelCalls.Context context) {
            this.span=span;this.parent=parent;this.model=model;this.attempt=attempt;this.context=context;
        }
    }
    /** 不可变阶段契约显式绑定；内部仍沿用原路由、预算与一次纠正规则。 */
    public <T> StructuredTurn<T> structuredObserved(String task,ModelRegistry.Selection selection,ModelInput input,
            ExecutionBudget budget,StructuredSchema<T> schema,com.example.ailab.contract.dto.ModelCalls.Context context,String pinned) {
        var previous=callExecution.get();var execution=new CallExecution(context);callExecution.set(execution);
        try {
            if(pinned!=null) {
                if(pinned.isBlank())throw new LabException("WORKFLOW_MODEL_ORIGIN_MISSING","缺少原结果的模型身份");
                budget.repair();
            }
            return structured(task,selection,input,budget,schema,pinned,pinned==null);
        } catch(RuntimeException failure) {
            if(execution.pending!=null) {execution.pending.error=failure instanceof LabException l?l.code():"INTERNAL_ERROR";execution.pending.span.fail(failure);}
            throw failure;
        } finally {
            completeCall(execution);
            if(previous==null)callExecution.remove();else callExecution.set(previous);
        }
    }
    private com.example.ailab.contract.dto.ModelCalls.CallRecord callRecord(CallExecution execution,PendingCall p,boolean ended) {
        return new com.example.ailab.contract.dto.ModelCalls.CallRecord(p.id,p.context,
                p.span.id().equals("missing-node")?null:p.span.id(),p.parent,p.model,p.attempt,
                registry.mock()?"SIMULATED":p.capture.sent?"SENT":"NOT_SENT",p.outcome,p.validation,p.error,
                p.capture.millis,p.validationMillis,p.diagnostic,p.started,ended?java.time.Instant.now():null);
    }
    private void completeCall(CallExecution execution) {
        if(execution==null||execution.pending==null)return;
        var p=execution.pending;execution.pending=null;
        long started=System.nanoTime();
        // 归档不可用时仍给图中的私人诊断一个保留期限，避免失败副本永久可读。
        var expires=p.started.plus(Duration.ofDays(Math.max(1,archiveRetention)));
        var input=com.example.ailab.contract.dto.ModelCalls.PayloadRef.unavailable("SAVE_FAILED",expires);
        var output=input;
        try {
            if(callArchive==null||!callArchive.enabled()) {input=com.example.ailab.contract.dto.ModelCalls.PayloadRef.unavailable("DISABLED",expires);output=input;}
            else {
                var detail=callArchive.finish(callRecord(execution,p,true),com.example.ailab.ai.runtime.TracePayloadCapture.redact(p.capture.input),
                        com.example.ailab.ai.runtime.TracePayloadCapture.redact(p.capture.output),registry.mock()?"SIMULATED":p.capture.sent?"INCOMPLETE":"NOT_CAPTURED");
                input=detail.input();output=detail.output();
            }
        } catch(RuntimeException unavailable) { LOG.warn("event=model.call_archive_failed callId={} code=SAVE_FAILED",p.id); }
        p.span.observation(new com.example.ailab.contract.dto.ModelCalls.Observation(p.id,p.outcome,p.validation,p.context.schemaId(),
                p.capture.millis,p.validationMillis,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),input,output));
        p.span.diagnostic(p.diagnostic);
        if(p.error!=null)p.span.fail(new LabException(p.error,"模型调用或校验失败"));
        p.span.close();
    }

    /** 工作流纠正固定原产出目标；这是该节点的唯一纠正，不再嵌套结构修复。 */
    public <T> StructuredTurn<T> structuredPinned(String task,ModelRegistry.Selection selection,ModelInput input,
                                                 ExecutionBudget budget,StructuredSchema<T> schema,String modelId) {
        if(modelId==null||modelId.isBlank())throw new LabException("WORKFLOW_MODEL_ORIGIN_MISSING","缺少原结果的模型身份，不能自动重选模型返工");
        budget.repair();
        return structured(task,selection,input,budget,schema,modelId,false);
    }

    private <T> StructuredTurn<T> structured(String task,ModelRegistry.Selection selection,ModelInput input,
                                             ExecutionBudget budget,StructuredSchema<T> schema,String pinned,boolean allowRepair) {
        var attempts = new ArrayList<ModelRoute.Attempt>();
        var preparedInput=new java.util.concurrent.atomic.AtomicReference<ModelInput.Prepared>();
        ModelInput capture=ModelInput.withOptions(ModelInput.reserveOutput(target->{var prepared=input.prepare(target);preparedInput.set(prepared);return prepared;},input.requiredOutputTokens()),input.options());
        Turn turn = generate(task, selection, capture, budget, schema, "", attempts, pinned, List.of());
        try {
            return new StructuredTurn<>(validateStructured(schema, turn, budget), turn);
        } catch (LabException invalid) {
            if (!invalid.code().equals("MODEL_STRUCTURED_INVALID")) throw invalid;
            markInvalid(attempts);
            budget.invalidStructure();
            if(!allowRepair)throw invalid;
            budget.repair();
            var execution = callExecution.get();
            if (execution != null && schema instanceof RecordSchema<T> record) {
                var plan = record.compressionPlan(turn.text(), referenceIds(turn.evidence()));
                if (plan.isPresent())
                    return compressSummary(task, selection, input, budget, record, plan.get(), turn, preparedInput.get(), attempts, execution);
            }
            // 修复固定当前成功目标，避免把结构错误当服务故障，或跨模型追求更有利的安全结果。
            var original=preparedInput.get();String draft=turn.text();
            ModelInput correction=target->{
                // 原回调重新核验来源；不能为了装入草稿而裁掉原参数或证据。
                var current=input.prepare(target);
                if(!current.messages().equals(original.messages())||!current.evidence().equals(original.evidence()))
                    throw new LabException("MODEL_CONTEXT_INSUFFICIENT","纠正窗口不足以保留原输入与证据");
                var messages=new ArrayList<>(original.messages());
                messages.add(AiMessage.from(draft));
                messages.add(UserMessage.from("上一回答是待修正数据；根据服务端字段诊断修正全部问题，只输出完整替换JSON，保留原目标、参数与来源。"));
                if(ModelInput.count(messages)+target.outputLimit()>target.contextWindow()) {
                    messages=new ArrayList<>(original.messages());
                    messages.add(UserMessage.from("DRAFT_OMITTED_FOR_CONTEXT：上次回答过大，无法完整回送。本轮保留全部原参数和服务端字段诊断，请从原输入重新生成完整JSON。"));
                    LOG.info("event=model.repair_input draftIncluded=false draftBytes={}",bytes(draft));
                }
                return new ModelInput.Prepared(messages,original.evidence(),ModelInput.count(messages));
            };
            turn = generate(task, selection, ModelInput.withOptions(ModelInput.reserveOutput(correction,input.requiredOutputTokens()),input.options()), budget, schema, schema.repairInstruction(invalid), attempts, turn.modelId(), List.of());
            try {
                return new StructuredTurn<>(validateStructured(schema, turn, budget), turn);
            } catch (LabException failed) {
                markInvalid(attempts);
                budget.invalidStructure();
                throw failed;
            }
        }
    }

    private <T> StructuredTurn<T> compressSummary(String task, ModelRegistry.Selection selection, ModelInput input,
            ExecutionBudget budget, RecordSchema<T> schema, LocalSummaryCompression.Plan plan, Turn original,
            ModelInput.Prepared originalInput, List<ModelRoute.Attempt> attempts, CallExecution execution) {
        var patchSchema = plan.schema();
        String prompt = plan.input();
        var fixed = ModelInput.fixed("执行服务端指定摘要的局部压缩。", List.of(), prompt);
        ModelInput compressionInput = target -> {
            // 用原目标重新执行来源/租约回调；压缩Schema预留不能令原输入误报容量不足。
            var checked = input.prepare(effectiveDefinition(input,registry.definition(original.modelId())));
            if (!checked.messages().equals(originalInput.messages()) || !checked.evidence().equals(originalInput.evidence()))
                throw new LabException("WORKFLOW_STATE_CONFLICT", "局部压缩期间原输入或来源已变化");
            var prepared = fixed.prepare(target);
            return new ModelInput.Prepared(prepared.messages(), originalInput.evidence(), prepared.inputTokens());
        };
        var context = execution.context;
        execution.attemptContext = new ModelCalls.Context(context.actor(), context.traceId(), context.taskId(), context.taskType(),
                context.stage(), "local-summary-compression", context.businessNodeId(), patchSchema.schemaId(), "1",
                Learning.digest(context.contractHash() + "\n" + patchSchema.instruction(Set.of()) + "\n" + prompt), context.sources());
        Turn compressed;
        LocalSummaryCompression.Replacements replacements;
        try {
            compressed = generate(task, selection, ModelInput.withOptions(compressionInput,input.options()), budget, patchSchema, "", attempts, original.modelId(), List.of());
            try { replacements = validateStructured(patchSchema, compressed, budget); }
            catch (LabException invalid) {
                if (invalid.code().equals("MODEL_STRUCTURED_INVALID")) { markInvalid(attempts);budget.invalidStructure(); }
                throw invalid;
            }
        } finally { execution.attemptContext = context; }
        String assembled = plan.assemble(replacements);
        // 合成校验属于本地后处理，不伪造提供方响应或归档，也不产生第二次付费纠正。
        try (var span = budget.trace().span("VALIDATION", "assembled_summary")) {
            var checked = schema.validateObserved(assembled, referenceIds(original.evidence()), "ASSEMBLED_RESULT");
            // 本地组合没有归档载荷的到期引用，只留规则和位置，避免正文摘录成为永久副本。
            if (checked.diagnostic() != null) {
                var diagnostic = checked.diagnostic();
                span.diagnostic(new ValidationDiagnostic(diagnostic.schemaId(), diagnostic.origin(), diagnostic.items().stream()
                        .map(i -> new ValidationDiagnostic.Item(i.issue(), i.pointer(), i.actualType(), null, i.start(), i.end(),
                                i.excerptTruncated(), i.line(), i.column(), i.locationBasis())).toList(),
                        diagnostic.collectionTruncated(), diagnostic.stoppedEarly()));
            }
            if (checked.failure() != null) { span.fail(checked.failure());throw checked.failure(); }
            span.output("局部摘要替换后的完整结果已通过原业务校验");
            return new StructuredTurn<>(checked.value(), compressed);
        }
    }

    /**
     * 提供方响应成功与结构验收分别记录，非法结构不会被成功模型节点掩盖。
     */
    private <T> T validateStructured(StructuredSchema<T> schema, Turn turn, ExecutionBudget budget) {
        var execution=callExecution.get();
        if(execution!=null&&execution.pending!=null) {
            var p=execution.pending;long started=System.nanoTime();
            try {
                T value;
                if(schema instanceof RecordSchema<T> record) {
                    var checked=record.validateObserved(turn.text(),referenceIds(turn.evidence()),"MODEL_RESPONSE");
                    p.diagnostic=checked.diagnostic();if(checked.failure()!=null)throw checked.failure();value=checked.value();
                } else value=schema.validate(turn.text(),referenceIds(turn.evidence()));
                p.validation="PASSED";return value;
            } catch(RuntimeException failure) {
                p.validation="FAILED";p.error=failure instanceof LabException l?l.code():"INTERNAL_ERROR";p.span.fail(failure);throw failure;
            } finally {p.validationMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);completeCall(execution);}
        }
        try (var span = budget.trace().span("VALIDATION", "structured_output")) {
            com.example.ailab.ai.runtime.TracePayloadCapture.input(span, turn.text());
            try {
                return schema.validate(turn.text(), referenceIds(turn.evidence()));
            } catch (RuntimeException failure) {
                span.fail(failure);
                throw failure;
            }
        }
    }

    public record StructuredTurn<T>(T value, Turn turn) {
    }

    /**
     * 标记实际付费成功但结构不合法的尝试，保留提供方用量。
     */
    private void markInvalid(List<ModelRoute.Attempt> attempts) {
        var a = attempts.remove(attempts.size() - 1);
        attempts.add(new ModelRoute.Attempt(a.modelId(), "MODEL_STRUCTURED_INVALID", a.reservedInputTokens(), a.countSource(),
                a.inputTokens(), a.outputTokens(), a.usageSource(), a.priceRef()));
    }

    /**
     * 引用白名单仅来源于目标实际收到的证据包。
     */
    private Set<String> referenceIds(List<EvidenceBundle> evidence) {
        var result = new HashSet<String>();
        evidence.forEach(e -> result.add(e.evidenceId()));
        return Set.copyOf(result);
    }

    /**
     * 每次发送前重装／复核，最多两候选三尝试，SDK重试零；缓存不保存请求期限。
     */
    private Turn generate(String task, ModelRegistry.Selection selection, ModelInput input, ExecutionBudget budget,
                          StructuredSchema<?> schema, String correction, List<ModelRoute.Attempt> log, String repairId, List<dev.langchain4j.agent.tool.ToolSpecification> tools) {
        if (selection == null) selection = ModelRegistry.Selection.auto();
        budget.turn();
        var required = !tools.isEmpty() ? Set.of("CHAT", "TOOLS") : schema == null ? Set.of("CHAT") : Set.of("CHAT", "STRUCTURED_OUTPUT");
        var repair = repairId != null;
        var baseDecision = registry.route(task, selection, required);
        if(repair&&!baseDecision.ids().contains(repairId))
            throw new LabException("MODEL_CAPABILITY_MISMATCH","原模型不满足当前路由条件，不能重选目标纠正");
        var decision = repair ? new ModelRegistry.Decision(baseDecision.profile(), baseDecision.mode(), List.of(repairId)) : baseDecision;
        var ids = decision.ids();
        int attempts = 0;
        String failure = "MODEL_UNAVAILABLE";
        var blockedQuotas = new HashSet<String>();
        for (String id : ids) {
            var d = effectiveDefinition(input,registry.definition(id));
            if (blockedQuotas.contains(d.quotaGroup())) continue;
            if (input.requiredOutputTokens() > d.outputLimit()) {
                failure = "MODEL_CONTEXT_INSUFFICIENT";
                if (decision.mode().equals("EXACT") || repair)
                    throw new LabException(failure, "完整输出估计超过实际注册模型输出窗口");
                continue;
            }
            for (int retry = 0; retry < 2 && attempts < registry.configuration().failover().maxAttemptsPerLogicalCall(); retry++) {
                budget.check();
                var permit = health.acquire(id, d.quotaGroup());
                if (permit == null) break;
                boolean global = false, local = false, sent = false;
                var semaphore = targetConcurrency.computeIfAbsent(id, key -> new Semaphore(d.maxConcurrency(), true));
                Integer inputUsage = null, outputUsage = null;
                int reserved = 0;
                String outcome = "MODEL_INVALID_OUTPUT";
                var raw = new java.util.concurrent.atomic.AtomicReference<String>();
                com.example.ailab.contract.context.TraceContext.Span modelSpan = null;
                FeeReservation fee = null;
                boolean feeSending = false;
                try {
                    // 先等目标许可，避免低并发目标的排队请求占满共享许可；等待不消费实际尝试额度。
                    var waitDeadline = java.time.Instant.now().plus(budget.timeout());
                    acquire(semaphore, budget, waitDeadline);
                    local = true;
                    acquire(concurrency, budget, waitDeadline);
                    global = true;
                    // 结构指令也计入目标窗口；重装时增加同等预留，不截系统规则和当前问题。
                    var target = schema == null ? d : withReservedSchema(d, schema, correction);
                    var prepared = input.prepare(target);
                    var messages = new ArrayList<>(prepared.messages());
                    if (!id.equals(ids.get(0)) && messages.stream().anyMatch(m -> m instanceof ToolExecutionResultMessage
                            || m instanceof AiMessage a && a.hasToolExecutionRequests()))
                        throw new LabException("MODEL_CONTEXT_NOT_PORTABLE", "工具协议状态无法安全切换目标");
                    if (schema != null)
                        messages.add(SystemMessage.from(schema.instruction(referenceIds(prepared.evidence())) + correction));
                    reserved = ModelInput.count(messages) + (tools.isEmpty() ? 0 : TextWindow.count(tools.toString()) + 256);
                    if (reserved + d.outputLimit() > d.contextWindow())
                        throw new LabException("MODEL_CONTEXT_INSUFFICIENT", "目标窗口不足以容纳结构约束");
                    // 金额和词元预留先于持久执行权消费；执行权失败只释放尚未发送的意图。
                    if (fees != null)
                        fee = fees.reserve(budget, id, "CHAT", reserved, d.outputLimit(), d.priceRef(), registry.mock());
                    budget.attempt();
                    if (fee != null) {
                        fees.sending(fee);
                        feeSending = true;
                    }
                    attempts++;
                    sent = true;
                    // 额度可靠消费成功之后才登记实际尝试，每次失败、修复和备用各一叶节点。
                    modelSpan = budget.trace().span("MODEL", "chat");
                    var execution=callExecution.get();
                    if(execution!=null) {
                        execution.pending=new PendingCall(modelSpan,budget.trace().currentSpanId(),id,attempts,execution.attemptContext);
                        try {if(callArchive!=null&&callArchive.enabled())callArchive.begin(callRecord(execution,execution.pending,false));}
                        catch(RuntimeException unavailable) {LOG.warn("event=model.call_archive_begin_failed code=SAVE_FAILED");}
                    }
                    if (budget.trace().capturesPayloads()) budget.trace().payloadSources(prepared.evidence().stream()
                            .map(e -> new SourceDependency(e.document().knowledgeBaseId(), e.document().id(), e.document().documentVersion())).toList());
                    if(execution==null)com.example.ailab.ai.runtime.TracePayloadCapture.messages(modelSpan, messages);
                    else modelSpan.input("调用输入已独立登记；按需查看全文");
                    budget.lastModelNode(modelSpan.id());
                    if (registry.mock()) {
                        if (d.modelName().contains("timeout"))
                            throw new LabException("MODEL_TIMEOUT", "模拟主模型超时");
                        outcome = "SUCCESS";
                        health.success(permit);
                        String text = "Mock 验证结果（非真实模型回答）：\n" + messages.get(messages.size() - 1);
                        if (schema instanceof KnowledgeAnswerSchema)
                            text = "{\"status\":\"NEEDS_INPUT\",\"answer\":\"Mock结构验证（非真实模型）\",\"references\":[]}";
                        com.example.ailab.ai.runtime.TracePayloadCapture.output(modelSpan, text);
                        log.add(usage(id, outcome, reserved, null, null, d));
                        budget.observe(log.get(log.size() - 1));
                        return turn(text, id, null, null, true, AiMessage.from(text), decision, log, prepared.evidence());
                    }
                    DeadlineHttpClient.CURRENT.set(new DeadlineHttpClient.Scope(budget, d.timeoutSeconds(), raw,
                            execution==null?null:execution.pending.capture,d.outputLimit(),d.providerId().startsWith("deepseek")?input.options().reasoningEffort():null,responses(d)));
                    var request = ChatRequest.builder().messages(messages);
                    if (!tools.isEmpty()) request.toolSpecifications(tools);
                    if (schema != null) request.responseFormat(ResponseFormat.builder().type(ResponseFormatType.JSON)
                            .jsonSchema(d.capabilities().contains("JSON_SCHEMA") ? schema.schema() : null).build());
                    var response = chatClient(id).chat(request.build());
                    var usage = response.tokenUsage();
                    inputUsage = usage == null ? null : usage.inputTokenCount();
                    outputUsage = usage == null ? null : usage.outputTokenCount();
                    String text = response.aiMessage().text();
                    if(execution!=null)modelSpan.output("提供方响应已接收；校验结果在同一节点登记");
                    else com.example.ailab.ai.runtime.TracePayloadCapture.output(modelSpan, response.aiMessage().hasToolExecutionRequests()
                            ? java.util.stream.Stream.concat(text == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(text),
                                    response.aiMessage().toolExecutionRequests().stream().map(tool -> tool.arguments())).toList() : text);
                    String protocolFailure = responsesFailure(d, raw.get());
                    if (protocolFailure != null) throw new LabException(protocolFailure, "模型响应未完成");
                    // SDK可能只返回文字，原始协议refusal必须独立检查，不能修复安全拒绝。
                    if (refused(raw.get()) || response.finishReason() == FinishReason.CONTENT_FILTER)
                        throw new LabException("MODEL_REFUSED", "模型拒绝本次请求");
                    if (response.finishReason() == FinishReason.LENGTH)
                        throw new LabException("MODEL_TRUNCATED", "模型输出已截断");
                    if (response.aiMessage().hasToolExecutionRequests() || response.finishReason() == FinishReason.TOOL_EXECUTION) {
                        if (tools.isEmpty() || !response.aiMessage().hasToolExecutionRequests())
                            throw new LabException("MODEL_TOOL_CALL_PENDING", "当前入口不接受工具申请");
                        budget.check();
                        health.success(permit);
                        outcome = "SUCCESS";
                        log.add(usage(id, "TOOL_REQUEST", reserved, inputUsage, outputUsage, d));
                        budget.observe(log.get(log.size() - 1));
                        var pending = turn(text, id, inputUsage, outputUsage, false, response.aiMessage(), decision, log, prepared.evidence());
                        return new Turn(text, id, inputUsage, outputUsage, false, "TOOL_EXECUTION", response.aiMessage(), pending.route(), prepared.evidence());
                    }
                    if (response.finishReason() != FinishReason.STOP)
                        throw new LabException("MODEL_INVALID_OUTPUT", "模型结束状态不合法");
                    if (text == null || text.isBlank())
                        throw new LabException("MODEL_INVALID_OUTPUT", "模型未返回可交付文本");
                    budget.check();
                    health.success(permit);
                    outcome = "SUCCESS";
                    log.add(usage(id, outcome, reserved, inputUsage, outputUsage, d));
                    budget.observe(log.get(log.size() - 1));
                    return turn(text, id, inputUsage, outputUsage, false, response.aiMessage(), decision, log, prepared.evidence());
                } catch (RuntimeException error) {
                    String protocolFailure = responsesFailure(d, raw.get());
                    failure = refused(raw.get()) ? "MODEL_REFUSED" : protocolFailure != null ? protocolFailure : classify(error);
                    LOG.warn("event=model.attempt_failed modelId={} attempt={} code={}", id, attempts, failure,
                            com.example.ailab.contract.error.DiagnosticFailure.sanitized(error));
                    outcome = failure;
                    if (!sent || !Set.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE", "MODEL_RATE_LIMITED", "MODEL_CONFIGURATION_ERROR").contains(failure))
                        throw new LabException(failure, "模型调用失败");
                    var http = transportFailure(error);
                    boolean limited = failure.equals("MODEL_RATE_LIMITED");
                    if (failure.equals("MODEL_CONFIGURATION_ERROR")) health.configurationError(permit);
                    else health.failure(permit, d.quotaGroup(), limited, http == null ? null : http.retryAfter);
                    if (limited) blockedQuotas.add(d.quotaGroup());
                    if (!failure.equals("MODEL_UNAVAILABLE") || permit.probe()) break;
                } finally {
                    // 拒绝／SDK解析失败也可能已有usage；只接受协议中的非负整数，不从估计补成真实值。
                    if (sent && raw.get() != null && (inputUsage == null || outputUsage == null)) {
                        if (inputUsage == null) inputUsage = rawUsage(raw.get(), responses(d) ? "input_tokens" : "prompt_tokens");
                        if (outputUsage == null) outputUsage = rawUsage(raw.get(), responses(d) ? "output_tokens" : "completion_tokens");
                    }
                    if (sent && !outcome.equals("SUCCESS")) {
                        log.add(usage(id, outcome, reserved, inputUsage, outputUsage, d));
                        budget.observe(log.get(log.size() - 1));
                    }
                    if (modelSpan != null) {
                        modelSpan.model(id, task, decision.profile(), registry.configuration().routing().policyVersion(),
                                repair ? "PINNED_CONTINUATION_OR_REPAIR" : !id.equals(ids.get(0)) ? "COMPATIBLE_FALLBACK" : "ORDERED_COMPATIBLE_HEALTHY",
                                attempts, inputUsage, outputUsage, registry.mock() ? "SIMULATED" : inputUsage == null || outputUsage == null ? "UNKNOWN" : "PROVIDER");
                        if (!outcome.equals("SUCCESS")) modelSpan.fail(new LabException(outcome, "模型失败"));
                        if(callExecution.get()==null)modelSpan.close();
                    }
                    LOG.info("event=model.attempt_complete modelId={} attempt={} outcome={} inputTokens={} outputTokens={} mock={}", id, attempts, outcome, inputUsage, outputUsage, registry.mock());
                    DeadlineHttpClient.CURRENT.remove();
                    health.release(permit);
                    if (local) semaphore.release();
                    if (global) concurrency.release();
                    // 先释放运行资源再可靠结算；结算故障不引发第二次远程请求。
                    try {
                        if (fee != null) {
                            if (feeSending) fees.complete(fee, inputUsage, outputUsage, outcome);
                            else fees.release(fee);
                        }
                    } finally {
                        var execution=callExecution.get();
                        if(execution!=null&&execution.pending!=null) {
                            execution.pending.outcome=outcome;
                            if(!outcome.equals("SUCCESS")) {execution.pending.error=outcome;completeCall(execution);}
                        }
                    }
                }
            }
        }
        // 配置明确关闭备用时，保留单目标失败原因，不能将主动禁用备用当作失败根因。
        boolean fallbackDisabled = !registry.configuration().profiles().get(decision.profile()).allowFallback();
        throw new LabException(decision.mode().equals("EXACT") || repair || fallbackDisabled || failure.equals("MODEL_CONTEXT_INSUFFICIENT") ? failure : ids.size() < 2 ? "NO_COMPATIBLE_FALLBACK" : failure,
                "模型暂不可用，未获得合法结果");
    }

    /** 许可等待使用同一截止时间，定期核验取消／租约，取得后核验失败也必须归还许可。 */
    private void acquire(Semaphore semaphore, ExecutionBudget budget, java.time.Instant waitDeadline) {
        boolean waiting = false;
        while (true) {
            long remaining = Duration.between(java.time.Instant.now(), waitDeadline).toMillis();
            if (remaining <= 0) throw new LabException("BUDGET_EXCEEDED", "模型并发许可等待已超时");
            try {
                // 空闲时公平地直接获取；只在排队时重复核验，避免每次调用额外多次读取全部来源。
                if (!semaphore.tryAcquire(waiting ? Math.min(250, remaining) : 0, TimeUnit.MILLISECONDS)) {
                    waiting = true;
                    budget.check();
                    continue;
                }
                try {
                    if (waiting) budget.check();
                    if (!java.time.Instant.now().isBefore(waitDeadline))
                        throw new LabException("BUDGET_EXCEEDED", "模型并发许可等待已超时");
                    return;
                } catch (RuntimeException failure) {
                    semaphore.release();
                    throw failure;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new LabException("REQUEST_CANCELLED", "模型并发许可等待已中断");
            }
        }
    }

    /**
     * 结构提示和纠正诊断参与窗口预留，重装不能漏计约束。
     */
    private ModelProperties.Definition withReservedSchema(ModelProperties.Definition d, StructuredSchema<?> schema, String correction) {
        int reserve = TextWindow.count(schema.instruction(Set.of("E1", "E2", "E3", "E4", "E5", "E6")) + correction) + 256;
        return new ModelProperties.Definition(d.providerId(), d.endpoint(), d.modelName(), d.credentialRef(), d.enabled(), d.capabilities(),
                d.qualityTags(), d.dataClassifications(), d.contextWindow(), d.outputLimit() + reserve, d.dimensions(), d.timeoutSeconds(), d.quotaGroup(), d.priceRef(), d.maxConcurrency());
    }

    /**
     * 客户端缓存仅注册ID，生命周期随应用；密钥不进入cache key或trace。
     */
    private dev.langchain4j.model.chat.ChatModel chatClient(String id) {
        return chatClients.computeIfAbsent(id, key -> {
            var d = registry.definition(key);
            if (responses(d))
                return OpenAiResponsesChatModel.builder().baseUrl(d.endpoint()).apiKey(registry.credential(key)).modelName(d.modelName())
                        .httpClientBuilder(new DeadlineHttpClient.Builder(transport)).maxOutputTokens(d.outputLimit())
                        .store(false).strictJsonSchema(true).logRequests(false).logResponses(false).build();
            return OpenAiChatModel.builder().baseUrl(d.endpoint()).apiKey(registry.credential(key)).modelName(d.modelName())
                    .httpClientBuilder(new DeadlineHttpClient.Builder(transport)).maxRetries(0).maxCompletionTokens(d.outputLimit())
                    .strictJsonSchema(true).logRequests(false).logResponses(false).build();
        });
    }

    /**
     * 公开验证缓存数量而非客户端对象，禁止暴露认证头。
     */
    public int cachedChatClients() {
        return chatClients.size();
    }

    /**
     * 入口先验证显式逻辑选项，不消耗embedding或取得会话执行权。
     */
    public void validateSelection(String profile) {
        registry.validateProfile(profile);
    }

    /**
     * 每次成功路由包含本逻辑调用全部失败／备用／修复尝试。
     */
    private Turn turn(String text, String id, Integer in, Integer out, boolean mock, AiMessage raw,
                      ModelRegistry.Decision decision, List<ModelRoute.Attempt> log, List<EvidenceBundle> evidence) {
        var c = registry.configuration().routing();
        var route = new ModelRoute(c.policyVersion(), c.qualityVersion(), decision.profile(), decision.mode(), id,
                log.stream().anyMatch(a -> a.outcome().equals("MODEL_STRUCTURED_INVALID")) ? "BOUNDED_REPAIR" : !id.equals(decision.ids().get(0)) ? "COMPATIBLE_FALLBACK" : "ORDERED_COMPATIBLE_HEALTHY",
                decision.ids().stream().skip(decision.ids().indexOf(id) + 1L).toList(), log);
        return new Turn(text, id, in, out, mock, "STOP", raw, route, evidence);
    }

    /**
     * 失败无usage明确UNKNOWN；保守计数与价格引用均不当真实金额。
     */
    private ModelRoute.Attempt usage(String id, String outcome, int reserved, Integer in, Integer out, ModelProperties.Definition d) {
        return new ModelRoute.Attempt(id, outcome, reserved, TextWindow.COUNT_SOURCE, in, out, in == null || out == null ? "UNKNOWN" : "PROVIDER", d.priceRef());
    }

    /**
     * 提供方合法refusal字段独立分类；协议损坏仍由SDK失败出口处理。
     */
    private boolean refused(String body) {
        try {
            var root = JSON.readTree(body);
            var refusal = root.path("choices").path(0).path("message").path("refusal");
            if (!refusal.isMissingNode() && !refusal.isNull() && !refusal.asText().isBlank()) return true;
            for (var item : root.path("output"))
                for (var content : item.path("content"))
                    if (content.path("type").asText().equals("refusal")) return true;
            return false;
        } catch (Exception invalid) {
            return false;
        }
    }

    private boolean responses(ModelProperties.Definition definition) {
        return definition.providerId().equals("deepseek-responses");
    }
    private static ModelProperties.Definition effectiveDefinition(ModelInput input,ModelProperties.Definition d){
        int requested=input.options().outputTokens();if(requested==0)return d;
        int limit=Math.min(d.outputLimit(),Math.max(requested,input.requiredOutputTokens()));
        return new ModelProperties.Definition(d.providerId(),d.endpoint(),d.modelName(),d.credentialRef(),d.enabled(),d.capabilities(),d.qualityTags(),d.dataClassifications(),d.contextWindow(),limit,d.dimensions(),d.timeoutSeconds(),d.quotaGroup(),d.priceRef(),d.maxConcurrency());
    }

    /** Responses状态与正文分别验收；SDK解析失败也可从原始协议保留拒绝、截断及真实用量。 */
    private String responsesFailure(ModelProperties.Definition definition, String body) {
        if (!responses(definition) || body == null) return null;
        try {
            var root = JSON.readTree(body);
            if (refused(body)) return "MODEL_REFUSED";
            if (root.path("status").asText().equals("completed")) return null;
            if (root.path("status").asText().equals("incomplete")) {
                String reason = root.path("incomplete_details").path("reason").asText();
                if (reason.equals("content_filter")) return "MODEL_REFUSED";
                if (reason.equals("max_output_tokens")) return "MODEL_TRUNCATED";
            }
            return "MODEL_INVALID_OUTPUT";
        } catch (Exception invalid) {
            return "MODEL_INVALID_OUTPUT";
        }
    }

    /**
     * 非正常结果的合法用量字段仍保留，其他情况明确未知。
     */
    private Integer rawUsage(String body, String field) {
        try {
            var value = JSON.readTree(body).path("usage").path(field);
            return value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0 ? value.intValue() : null;
        } catch (Exception invalid) {
            return null;
        }
    }

    /**
     * embedding 单向量空间，无聊天主备；入库使用独立有限 attempts 预算。
     */
    public Vectors embed(List<String> texts, ExecutionBudget budget) {
        var ids = registry.candidates("EMBEDDING", Set.of("EMBEDDING"));
        String id = ids.get(0);
        var d = registry.definition(id);
        if (texts.isEmpty() || texts.size() > 32 || texts.stream().mapToInt(this::bytes).sum() > 16000)
            throw new LabException("INGESTION_BUDGET_EXCEEDED", "Embedding 批次输入超过限额");
        acquire(concurrency, budget, java.time.Instant.now().plus(budget.timeout()));
        com.example.ailab.contract.context.TraceContext.Span embeddingSpan = null;
        Integer used = null;
        FeeReservation fee = null;
        boolean feeSending = false;
        String outcome = "MODEL_INVALID_OUTPUT";
        var raw = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            if (fees != null)
                fee = fees.reserve(budget, id, "EMBEDDING", texts.stream().mapToLong(this::bytes).sum(), 0, d.priceRef(), registry.mock());
            budget.attempt();
            if (fee != null) {
                fees.sending(fee);
                feeSending = true;
            }
            embeddingSpan = budget.trace().span("EMBEDDING", "embed");
            com.example.ailab.ai.runtime.TracePayloadCapture.input(embeddingSpan, texts);
            if (registry.mock()) {
                var vectors = new ArrayList<List<Float>>();
                for (String text : texts) {
                    float[] vector = new float[d.dimensions()];
                    text.codePoints().forEach(cp -> vector[Math.floorMod(cp, vector.length)] += 1);
                    double norm = 0;
                    for (float v : vector) norm += v * v;
                    norm = Math.sqrt(norm);
                    var result = new ArrayList<Float>();
                    for (float v : vector) result.add(norm == 0 ? 0 : (float) (v / norm));
                    vectors.add(List.copyOf(result));
                }
                outcome = "SUCCESS";
                com.example.ailab.ai.runtime.TracePayloadCapture.output(embeddingSpan, Map.of("vectors", vectors));
                return new Vectors(List.copyOf(vectors), d.modelName(), true);
            }
            DeadlineHttpClient.CURRENT.set(new DeadlineHttpClient.Scope(budget, d.timeoutSeconds(), raw));
            var model = embeddingClients.computeIfAbsent(id, key -> OpenAiEmbeddingModel.builder().baseUrl(d.endpoint())
                    .apiKey(registry.credential(key)).modelName(d.modelName()).dimensions(d.dimensions())
                    .httpClientBuilder(new DeadlineHttpClient.Builder(transport)).maxRetries(0).maxSegmentsPerBatch(32)
                    .logRequests(false).logResponses(false).build());
            var result = model.embedAll(texts.stream().map(TextSegment::from).toList());
            // 即便向量结构错误，已获提供方用量仍须保存，不能被结果校验覆盖。
            used = result.tokenUsage() == null ? null : result.tokenUsage().inputTokenCount();
            var vectors = result.content().stream().map(e -> {
                var values = new ArrayList<Float>();
                for (float v : e.vector()) values.add(v);
                return List.copyOf(values);
            }).toList();
            if (vectors.size() != texts.size())
                throw new LabException("MODEL_INVALID_OUTPUT", "Embedding 返回数量不符");
            com.example.ailab.ai.runtime.TracePayloadCapture.output(embeddingSpan, Map.of("vectors", vectors));
            outcome = "SUCCESS";
            return new Vectors(vectors, d.modelName(), false, used);
        } catch (LabException e) {
            outcome = e.code();
            if (embeddingSpan != null) embeddingSpan.fail(e);
            throw e;
        } catch (RuntimeException e) {
            LOG.error("event=embedding.failed code={}", classify(e), com.example.ailab.contract.error.DiagnosticFailure.sanitized(e));
            outcome = classify(e);
            if (embeddingSpan != null) embeddingSpan.fail(new LabException(classify(e), "嵌入失败"));
            throw new LabException(classify(e), "Embedding 调用失败");
        } finally {
            if (used == null && raw.get() != null) used = rawUsage(raw.get(), "prompt_tokens");
            if (embeddingSpan != null) {
                embeddingSpan.model(id, "EMBEDDING", "embedding", registry.configuration().routing().policyVersion(), "SINGLE_VECTOR_SPACE", budget.attempts(), used, null, registry.mock() ? "SIMULATED" : used == null ? "UNKNOWN" : "PROVIDER");
                embeddingSpan.close();
            }
            LOG.info("event=embedding.complete modelId={} outcome={} inputTokens={} mock={}", id, outcome, used, registry.mock());
            DeadlineHttpClient.CURRENT.remove();
            concurrency.release();
            if (fee != null) {
                if (feeSending) fees.complete(fee, used, 0, outcome);
                else fees.release(fee);
            }
        }
    }

    /**
     * 输入窗口采用保守 UTF-8 字节上界；真实计费不使用此估计代替供应商 usage。
     */
    private int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 仅明确瞬时故障允许切换，认证、限流和参数失败不重试。
     */
    private String classify(RuntimeException e) {
        // 预算／授权复核可能访问数据库；其底层Socket异常不能触发模型重试或主备切换。
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException) return "INTERNAL_ERROR";
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof LabException lab) return lab.code();
            if (cause instanceof DeadlineHttpClient.Failure http) {
                if (http.status == 429) return "MODEL_RATE_LIMITED";
                if (Set.of(401, 403, 400, 404, 422).contains(http.status)) return "MODEL_CONFIGURATION_ERROR";
                if (Set.of(500, 502, 503, 504).contains(http.status)) return "MODEL_UNAVAILABLE";
                return "MODEL_INVALID_OUTPUT";
            }
            String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout")) return "MODEL_TIMEOUT";
            if (name.contains("Authentication") || name.contains("Unauthorized")) return "ACCESS_DENIED";
            if (name.contains("RateLimit")) return "MODEL_RATE_LIMITED";
            if (name.contains("InternalServer") || cause instanceof java.io.IOException) return "MODEL_UNAVAILABLE";
        }
        return "MODEL_INVALID_OUTPUT";
    }

    /**
     * SDK包装异常仍保留我们自己的脱敏状态及Retry-After。
     */
    private DeadlineHttpClient.Failure transportFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause())
            if (cause instanceof DeadlineHttpClient.Failure f) return f;
        return null;
    }
}
