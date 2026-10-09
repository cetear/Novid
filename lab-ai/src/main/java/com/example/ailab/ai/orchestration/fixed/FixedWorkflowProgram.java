package com.example.ailab.ai.orchestration.fixed;

import com.example.ailab.ai.orchestration.WorkflowProgram;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

/** 程序规定步骤顺序；持久检查点和产物校验由业务步骤负责。 */
public record FixedWorkflowProgram<R>(List<Step> steps, Callable<R> result) implements WorkflowProgram<R> {
    public record Step(String id, Callable<?> action) {
        public Step { Objects.requireNonNull(action); if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("固定步骤ID无效"); }
    }
    public FixedWorkflowProgram {
        steps = List.copyOf(steps); Objects.requireNonNull(result);
        if (steps.size() > 4096 || steps.stream().map(Step::id).distinct().count() != steps.size()) throw new IllegalArgumentException("固定步骤重复或超过上限");
    }
    public static <R> FixedWorkflowProgram<R> of(Callable<R> action) { return new FixedWorkflowProgram<>(List.of(), action); }
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.FIXED; }
}
