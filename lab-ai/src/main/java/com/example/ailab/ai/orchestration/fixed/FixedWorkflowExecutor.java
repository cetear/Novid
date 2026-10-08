package com.example.ailab.ai.orchestration.fixed;

import com.example.ailab.ai.model.StructuredSchema;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.tools.ToolSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.FixedWorkflowStorePort;
import java.util.*;
import java.util.function.Supplier;

/** 程序定义依赖的固定节点执行器；完成观察按输入摘要复用，待完成节点保留原身份。 */
public final class FixedWorkflowExecutor {
    private final FixedWorkflowStorePort store;
    private final TaskLease lease;
    private final ExecutionBudget budget;
    private final Learning.Baseline baseline;
    private final Runnable verify;
    public FixedWorkflowExecutor(FixedWorkflowStorePort store, TaskLease lease, ExecutionBudget budget,
                                 Learning.Baseline baseline, Runnable verify) {
        this.store = store; this.lease = lease; this.budget = budget; this.baseline = baseline; this.verify = verify;
    }
    public <T> T node(String id, String stage, Object input, StructuredSchema<T> schema, Supplier<T> execute) {
        budget.check(); verify.run();
        String hash = ToolSchema.hash(Map.of("baseline", baseline, "node", id, "input", input));
        try (var span = budget.trace().span("AGENT", id); var activation = budget.activate(span.context())) {
            var old = store.completed(lease, id, hash);
            if (old.isPresent()) { span.status("REUSED"); return schema.validate(old.get(), Set.of()); }
            store.begin(lease, id, stage, hash);
            T result = execute.get();
            String json = json(result);
            result = schema.validate(json, Set.of());
            verify.run();
            store.complete(lease, id, hash, json);
            return result;
        }
    }
    public void finish(String stage) { verify.run(); store.finishStage(lease, stage); }
    public static String json(Object value) {
        try { return ToolSchema.JSON.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("工作流JSON序列化失败", invalid); }
    }
}
