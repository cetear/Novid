package com.example.ailab.ai.tools;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.runtime.ExecutionBudget;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 统一调度显式登记的本地方法与MCP工具，执行策略和预算由服务端约束。
 */
@Component
public class ToolExecutionService implements AutoCloseable {
    public record Descriptor(String name, String version, String type, boolean enabled) {
    }

    private final LocalToolSource local;
    private final ToolRegistry registry;
    private final boolean enabled;

    /**
     * 媒体搜索通过受控端口装配，未配置时不暴露给模型。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void webImages(WebImageSearchPort webImages) {
        local.images(webImages);
    }

    private final java.util.concurrent.ThreadPoolExecutor executor = new java.util.concurrent.ThreadPoolExecutor(2, 2, 0,
            java.util.concurrent.TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<>(8), new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    public record Outcome(String toolCallId, String name, String version, String status, String operationId,
                          String result, boolean retryable, List<EvidenceBundle> evidence) {
    }

    /**
     * 独立业务能力端口不会回调问答用例。
     */
    public ToolExecutionService(KnowledgeCapabilityPort knowledge, KnowledgeSearchPort search) {
        this(knowledge, search, true);
    }

    /**
     * 工具开关影响暴露与实际执行，旧显式构造继续使用核心只读工具。
     */
    public ToolExecutionService(KnowledgeCapabilityPort knowledge, KnowledgeSearchPort search,
                                boolean enabled) {
        this(knowledge, search, enabled, 30);
    }

    /**
     * 工具包含授权、远程向量与检索，独立期限可配置且仍受共享任务剩余时间限制。
     */
    public ToolExecutionService(KnowledgeCapabilityPort knowledge, KnowledgeSearchPort search, boolean enabled, int timeoutSeconds) {
        this(knowledge, search, enabled, timeoutSeconds, List.of());
    }

    /**
     * 来源由Spring装配，核心入口不再按具体工具名分派。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public ToolExecutionService(KnowledgeCapabilityPort knowledge, KnowledgeSearchPort search,
                                @org.springframework.beans.factory.annotation.Value("${lab.tools.enabled:true}") boolean enabled,
                                @org.springframework.beans.factory.annotation.Value("${lab.tools.timeout-seconds:90}") int timeoutSeconds,
                                List<ToolSource> sources) {
        local = new LocalToolSource(knowledge, search, timeoutSeconds);
        this.enabled = enabled;
        var values = new ArrayList<>(local.tools());
        sources.forEach(source -> values.addAll(source.tools()));
        registry = new ToolRegistry(values);
    }

    /**
     * 请求暴露与执行重查共用策略；Skill不进入这个目录。
     */
    public List<ToolDefinition> definitions(UserContext actor, String task) {
        ToolPolicy.actor(actor);
        if (!ToolPolicy.TASKS.contains(task)) throw LabException.invalid("未知工具任务");
        var values = registry.entries().stream().filter(t -> ToolPolicy.visible(t, actor, task, enabled)).toList();
        if (values.stream().anyMatch(t -> t.source().equals("local") && t.definition().requiredCapabilities().contains("KNOWLEDGE_READ")))
            local.authorize(actor);
        return values.stream().map(RegisteredTool::definition).toList();
    }

    public Map<String, String> contracts() {
        return registry.contracts();
    }

    /**
     * 已绑定任务只可调用原契约；目录升级不能按同名替换执行目标。
     */
    public void verifyContracts(Map<String, String> expected) {
        var current = contracts();
        expected.forEach((name, hash) -> {
            if (!hash.equals(current.get(name))) throw new LabException("TOOL_CONTRACT_CHANGED", "任务工具契约已变化");
        });
    }

    /**
     * 受控执行重新检查暴露集合、严格JSON、当前授权、期限与共享预算；不做自动重试。
     */
    public Outcome execute(UserContext actor, ScopeRequest scope, String task, String callId, String name,
                           String arguments, String evidenceId, ExecutionBudget budget,
                           java.util.function.Function<String, ModelVector> vector) {
        try (var span = budget.trace().span("TOOL", registry.all().stream().anyMatch(t -> t.name().equals(name)) ? name : "unregistered_tool")) {
            span.tool(callId);
            try (var active = budget.activate(span.context())) {
                try {
                    com.example.ailab.ai.runtime.TracePayloadCapture.input(span, arguments);
                    var result = executeObserved(actor, scope, task, callId, name, arguments, evidenceId, budget, vector);
                    if (budget.trace().capturesPayloads()) budget.trace().payloadSources(result.evidence().stream()
                            .map(e -> new SourceDependency(e.document().knowledgeBaseId(), e.document().id(), e.document().documentVersion())).toList());
                    com.example.ailab.ai.runtime.TracePayloadCapture.output(span, result.result());
                    span.status(result.status());
                    return result;
                } catch (RuntimeException error) {
                    span.fail(error);
                    throw error;
                }
            }
        }
    }

    /**
     * 真实工具调用由外层节点关联，队列拒绝与等待超时同样记录。
     */
    private Outcome executeObserved(UserContext actor, ScopeRequest scope, String task, String callId, String name,
                                    String arguments, String evidenceId, ExecutionBudget budget,
                                    java.util.function.Function<String, ModelVector> vector) {
        var d = registry.require(name);
        if (!d.enabled() || !enabled) throw new LabException("TOOL_DISABLED", "工具未启用");
        if (definitions(actor, task).stream().noneMatch(t -> t.name().equals(name))) throw LabException.denied();
        var args = registry.arguments(name, arguments);
        budget.check();
        long toolDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(d.timeoutSeconds());
        java.util.concurrent.Future<Outcome> pending;
        var parent = budget.trace();
        // 工具池显式传递父节点，embedding叶节点不会错误挂到另一个角色。
        try {
            pending = executor.submit(() -> {
                try (var active = budget.activate(parent)) {
                    return perform(actor, scope, task, callId, name, evidenceId, budget, vector, d, args, toolDeadline);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            throw new LabException("RATE_LIMITED", "工具执行池已满");
        }
        try {
            long remainingTask = budget.timeout().toNanos();
            long wait = Math.min(toolDeadline - System.nanoTime(), remainingTask);
            if (wait <= 0) throw new java.util.concurrent.TimeoutException();
            var result = pending.get(wait, java.util.concurrent.TimeUnit.NANOSECONDS);
            if (toolDeadline - System.nanoTime() <= 0) throw new java.util.concurrent.TimeoutException();
            return result;
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new Outcome(callId, name, d.version(), "TIMEOUT", null, "工具超时，不交付迟到正文", false, List.of());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new LabException("REQUEST_CANCELLED", "工具等待已取消");
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof LabException lab) throw lab;
            return new Outcome(callId, name, d.version(), "FAILED", null, "TOOL_FAILED", false, List.of());
        } finally {
            // 等待时间求值也可能因取消／预算到期抛异常；所有退出路径统一清理。
            if (!pending.isDone()) {
                pending.cancel(true);
                if (pending instanceof Runnable queued) executor.remove(queued);
            }
        }
    }

    /**
     * 工具线程只做只读端口调用；超时后即使底层未中断也不交付结果或调度下一模型。
     */
    private Outcome perform(UserContext actor, ScopeRequest scope, String task, String callId, String name, String evidenceId,
                            ExecutionBudget budget, java.util.function.Function<String, ModelVector> vector, ToolDefinition d,
                            com.fasterxml.jackson.databind.JsonNode args, long toolDeadline) {
        budget.check();
        try {
            if (!ToolPolicy.visible(registry.binding(name), actor, task, enabled)) throw LabException.denied();
            budget.tool();
            var value = registry.binding(name).executor().execute(new ToolInvocationContext(actor, scope, task, callId, evidenceId, budget, toolDeadline, vector), args);
            budget.check();
            if (!ToolPolicy.visible(registry.binding(name), actor, task, enabled)) throw LabException.denied();
            if (toolDeadline - System.nanoTime() <= 0)
                return new Outcome(callId, name, d.version(), "TIMEOUT", null, "工具结果超时，不交付正文", false, List.of());
            if (TextWindow.count(value.text()) > 12000) throw new LabException("BUDGET_EXCEEDED", "工具输出超限");
            return new Outcome(callId, name, d.version(), "SUCCESS", null, value.text().replaceAll("[\\p{Cntrl}&&[^\\n\\r\\t]]", ""), false, value.evidence());
        } catch (LabException error) {
            if (Set.of("STALE_EXECUTION", "BUDGET_EXCEEDED", "REQUEST_CANCELLED").contains(error.code())) throw error;
            String status = switch (error.code()) {
                case "ACCESS_DENIED" -> "DENIED";
                case "INVALID_ARGUMENTS" -> "INVALID_ARGUMENTS";
                default -> "FAILED";
            };
            return new Outcome(callId, name, d.version(), status, null, error.code(), false, List.of());
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new LabException("REQUEST_CANCELLED", "工具已取消");
        } catch (Exception error) {
            return new Outcome(callId, name, d.version(), "FAILED", null, "TOOL_FAILED", false, List.of());
        }
    }

    public record ModelVector(List<Float> vector, String version) {
    }

    /**
     * 应用关闭释放有界只读工具池，不创建无界线程或后台重试。
     */
    @jakarta.annotation.PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 每次暴露工具先重新核验当前身份。
     */
    public List<Descriptor> definitions(UserContext actor) {
        local.authorize(actor);
        return registry.all().stream().filter(d -> !d.name().equals("search_web_images")).map(d -> new Descriptor(d.name(), d.version(), d.type(), enabled && d.enabled())).toList();
    }

    /**
     * 受控统计工具只读取真实数值。
     */
    public KnowledgeStatistics statistics(UserContext actor, ScopeRequest scope, ExecutionBudget budget) {
        check("get_knowledge_statistics", budget);
        return local.statistics(actor, scope, budget);
    }

    /**
     * 搜索向量由 ModelGateway 产生，模型不能提交伪造 scope。
     */
    public List<EvidenceBundle> search(UserContext actor, ScopeRequest scope, String question, List<Float> vector, String version, int bytes, ExecutionBudget budget) {
        check("search_knowledge", budget);
        return local.search(actor, scope, question, vector, version, bytes, budget);
    }

    /**
     * 最终交付前重新读取全部证据版本和真实原文范围。
     */
    public void verify(UserContext actor, ScopeRequest scope, List<EvidenceBundle> evidence) {
        local.verify(actor, scope, evidence);
    }

    /**
     * 注册、启用、预算是程序约束，未知工具不能执行任意代码。
     */
    private void check(String name, ExecutionBudget budget) {
        var d = registry.require(name);
        if (!enabled || !d.enabled()) throw new LabException("TOOL_DISABLED", "工具未启用");
        budget.tool();
    }
}
