package com.example.ailab.ai.orchestration;

import com.example.ailab.ai.model.*;
import com.example.ailab.ai.tools.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** 受限手写续轮：保留SDK消息，每个申请恰好一个结果，共享调用方预算。 */
public final class BoundedToolLoop {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Result(ModelGateway.Turn turn, List<ToolExchange> exchanges) { }
    private final ModelGateway models;
    private final ToolExecutionService tools;
    /** 编排只持有受控模型和工具入口，禁止SDK自动执行业务方法。 */
    public BoundedToolLoop(ModelGateway models, ToolExecutionService tools) { this.models = models; this.tools = tools; }

    /** 首轮可合法主备；工具状态开始后固定目标，不重复已成功调用。 */
    public Result run(UserContext actor, ScopeRequest scope, ModelRegistry.Selection selection, ModelInput initial,
                      ExecutionBudget budget, Runnable verify) {
        var specs = tools.definitions(actor, "KNOWLEDGE_QA").stream().map(ToolRegistry::specification).toList();
        var sent = new java.util.concurrent.atomic.AtomicReference<ModelInput.Prepared>();
        var turn = models.toolTurn("KNOWLEDGE_QA", selection, target -> {
            verify.run(); var prepared = initial.prepare(target); sent.set(prepared); return prepared;
        }, budget, specs, null);
        var messages = new ArrayList<>(sent.get().messages());
        var evidence = new ArrayList<>(turn.evidence());
        var exchanges = new ArrayList<ToolExchange>(); var used = new HashSet<String>();
        var attempts = new ArrayList<>(turn.route().attempts());
        while (turn.rawMessage() != null && turn.rawMessage().hasToolExecutionRequests()) {
            verify.run(); budget.check();
            var requests = normalize(turn.rawMessage().toolExecutionRequests(), used, exchanges.size());
            // 保留原始message的提供方属性和可选思考内容，只有缺失ID才补稳定本轮标识。
            messages.add(turn.rawMessage().toBuilder().toolExecutionRequests(requests).build());
            var results = new ArrayList<ToolExecutionResultMessage>();
            var resultNodes=new ArrayList<String>();
            for (var request : requests) {
                // 申请来自实际模型节点，参数和正文不进入排错记录。
                var requested=budget.trace().span("TOOL_REQUEST","request",null,null,
                        budget.lastModelNode()==null?List.of():List.of(budget.lastModelNode()));
                requested.tool(request.id()); resultNodes.add(requested.id());
                try(var active=budget.activate(requested.context())) {
                verify.run(); tools.verify(actor, scope, evidence);
                if (evidence.size() >= 6 && !request.name().equals("get_knowledge_statistics"))
                    throw new LabException("BUDGET_EXCEEDED", "工具证据包数已满");
                var outcome = tools.execute(actor, scope, "KNOWLEDGE_QA", request.id(), request.name(), request.arguments(),
                        "E" + (evidence.size() + 1), budget, query -> {
                            var vector = models.embed(List.of(query), budget);
                            return new ToolExecutionService.ModelVector(vector.vectors().get(0), vector.modelVersion());
                        });
                evidence.addAll(outcome.evidence());
                if (evidence.stream().mapToInt(e -> TextWindow.count(e.text()) + TextWindow.count(e.headingPath()) + 64).sum() > 4000)
                    throw new LabException("BUDGET_EXCEEDED", "续轮证据超过共享上限");
                String result = serialized(outcome);
                results.add(ToolExecutionResultMessage.from(request, result));
                exchanges.add(new ToolExchange(request.id(), request.name(), outcome.version(), request.arguments(), result));
                } catch(RuntimeException rejected) { requested.fail(rejected); throw rejected; }
                finally { requested.close(); }
            }
            paired(requests, results); messages.addAll(results);
            String pinned = turn.modelId(); var actualEvidence = List.copyOf(evidence);
            try(var continuation=budget.trace().span("CONTINUATION","tool_results",null,null,resultNodes);
                var active=budget.activate(continuation.context())) {
            try { turn = models.toolTurn("KNOWLEDGE_QA", selection, target -> {
                verify.run(); tools.verify(actor, scope, actualEvidence);
                return new ModelInput.Prepared(messages, actualEvidence, ModelInput.count(messages));
            }, budget, specs, pinned); }
            catch(RuntimeException failed) { continuation.fail(failed); throw failed; }
            }
            attempts.addAll(turn.route().attempts());
        }
        var route = turn.route();
        var combined = new ModelRoute(route.policyVersion(), route.qualityVersion(), route.profile(), route.routingMode(),
                turn.modelId(), "BOUNDED_TOOL_LOOP", List.of(), attempts);
        return new Result(new ModelGateway.Turn(turn.text(), turn.modelId(), turn.inputTokens(), turn.outputTokens(), turn.mock(),
                turn.finishReason(), turn.rawMessage(), combined, List.copyOf(evidence)), List.copyOf(exchanges));
    }
    /** 缺失ID补本轮稳定标识；重复ID跨轮也拒绝，不能借重放掩盖工具副作用。 */
    public static List<ToolExecutionRequest> normalize(List<ToolExecutionRequest> requests, Set<String> used, int offset) {
        if (requests.isEmpty() || requests.size() > 8) throw LabException.invalid("工具申请数量不合法");
        var normalized = new ArrayList<ToolExecutionRequest>();
        for (var request : requests) {
            String id = request.id() == null || request.id().isBlank() ? "local_tool_" + (offset + normalized.size()) : request.id();
            if (id.length() > 128 || !id.matches("[A-Za-z0-9_.:-]+") || !used.add(id))
                throw new LabException("TOOL_PAIR_INVALID", "工具标识缺失、重复或不合法");
            normalized.add(request.toBuilder().id(id).build());
        }
        return List.copyOf(normalized);
    }
    /** 缺失、重复、多余或错名结果禁止进入下一次SDK调用。 */
    public static void paired(List<ToolExecutionRequest> requests, List<ToolExecutionResultMessage> results) {
        var expected = new HashMap<String, String>(); var actual = new HashMap<String, String>();
        for (var request : requests) if (expected.put(request.id(), request.name()) != null) throw pairInvalid();
        for (var result : results) if (actual.put(result.id(), result.toolName()) != null) throw pairInvalid();
        if (!expected.equals(actual)) throw pairInvalid();
    }
    /** 协议不完整时稳定失败，不让框架推断或补造成功。 */
    private static LabException pairInvalid() { return new LabException("TOOL_PAIR_INVALID", "工具申请与结果不匹配"); }
    /** 工具正文是低信任数据；结果有明确状态且不含内部异常、执行地址或认证信息。 */
    private String serialized(ToolExecutionService.Outcome outcome) {
        try { return JSON.writeValueAsString(Map.of("toolCallId", outcome.toolCallId(), "toolVersion", outcome.version(),
                "status", outcome.status(), "result", outcome.result(), "retryable", outcome.retryable())); }
        catch (Exception failure) { throw new LabException("TOOL_RESULT_INVALID", "工具结果不能序列化"); }
    }
}
