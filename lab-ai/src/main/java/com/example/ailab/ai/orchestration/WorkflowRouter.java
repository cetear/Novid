package com.example.ailab.ai.orchestration;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;

/** 组合工作流登记与独立架构执行器；未知路由、失效绑定和程序不匹配均不降级。 */
@Component
public final class WorkflowRouter implements WorkflowRoutingPort {
    private record ExecutorKey(ExecutionArchitecture architecture, String version) { }
    private final Map<String, WorkflowExecutionBinding> routes;
    private final Map<ExecutorKey, ArchitectureExecutor> executors;

    public WorkflowRouter(List<WorkflowModule> modules, List<ArchitectureExecutor> engines) {
        var registered = new HashMap<ExecutorKey, ArchitectureExecutor>();
        for (var engine : engines) {
            var key = new ExecutorKey(Objects.requireNonNull(engine.architecture()), engine.version());
            if (key.version() == null || !key.version().matches("[a-zA-Z0-9.-]{1,64}") || registered.putIfAbsent(key, engine) != null)
                throw new IllegalStateException("架构执行器版本无效或重复登记");
        }
        var configured = new HashMap<String, WorkflowExecutionBinding>();
        var identities = new HashSet<String>();
        for (var module : modules) for (var entry : module.routes().entrySet()) {
            var type = entry.getKey(); var binding = Objects.requireNonNull(entry.getValue());
            if (type == null || !type.matches("[A-Z][A-Z0-9_]{0,63}") || configured.putIfAbsent(type, binding) != null
                    || !identities.add(binding.workflowId() + "@" + binding.workflowVersion()))
                throw new IllegalStateException("工作流类型或身份重复登记或无效");
            if (!registered.containsKey(key(binding))) throw new IllegalStateException("工作流绑定的架构执行器未登记");
        }
        routes = Map.copyOf(configured); executors = Map.copyOf(registered);
    }
    @Override public WorkflowExecutionBinding route(String type) {
        var binding = routes.get(type);
        if (binding == null) throw new LabException("WORKFLOW_NOT_REGISTERED", "此工作流尚未登记执行架构");
        return binding;
    }
    public void verify(String type, WorkflowExecutionBinding saved) {
        if (!route(type).equals(saved) || !executors.containsKey(key(saved)))
            throw new LabException("WORKFLOW_EXECUTOR_UNAVAILABLE", "任务绑定的工作流或架构执行器不可用");
    }
    public WorkflowExecutionBinding binding(TaskLease lease, WorkflowRunStorePort store) {
        var saved = store.binding(lease).orElseThrow(() -> new LabException("WORKFLOW_BINDING_MISSING", "任务缺少创建时的架构绑定，请重新发起任务"));
        verify(lease.request().taskType(), saved);
        return saved;
    }
    public <R> R execute(TaskLease lease, WorkflowRunStorePort store, ExecutionBudget budget, WorkflowProgram<R> program) {
        var saved = binding(lease, store);
        var executor = executors.get(key(saved));
        if (saved.architecture() != program.architecture() || !executor.supports(program))
            throw new LabException("WORKFLOW_ARCHITECTURE_MISMATCH", "执行程序与任务绑定的架构不一致");
        budget.check();
        try (var span = budget.trace().span("ARCHITECTURE", saved.architecture().name()); var active = budget.activate(span.context())) {
            try {
                com.example.ailab.ai.runtime.TracePayloadCapture.input(span, lease.request());
                var result = executor.execute(program, budget); budget.check();
                com.example.ailab.ai.runtime.TracePayloadCapture.output(span, result); return result;
            } catch (Exception failure) {
                Throwable cause = failure;
                while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) cause = cause.getCause();
                span.fail(cause);
                if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof TimeoutException) throw new LabException("BUDGET_EXCEEDED", "架构执行超过共享截止时间");
                throw new LabException(cause instanceof InterruptedException ? "REQUEST_CANCELLED" : "WORKFLOW_EXECUTION_FAILED", "架构执行未完成");
            }
        }
    }
    private static ExecutorKey key(WorkflowExecutionBinding binding) { return new ExecutorKey(binding.architecture(), binding.executorVersion()); }
}
