package com.example.ailab.ai.orchestration.planexecute;

import com.example.ailab.ai.orchestration.*;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import java.util.Objects;

/** 有限的计划—执行—评估—重规划循环；各轮共享原预算和截止时间。 */
@Component
public final class PlanExecuteExecutor implements ArchitectureExecutor {
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.PLAN_EXECUTE; }
    public String version() { return "plan-execute-v1"; }
    public boolean supports(WorkflowProgram<?> program) { return program instanceof PlanExecuteProgram<?, ?>; }
    public <R> R execute(WorkflowProgram<R> program, ExecutionBudget budget) throws Exception {
        if (!(program instanceof PlanExecuteProgram<?, R> planned)) throw new LabException("WORKFLOW_ARCHITECTURE_MISMATCH", "需要规划执行程序");
        return run(planned, budget);
    }
    private <P, R> R run(PlanExecuteProgram<P, R> program, ExecutionBudget budget) throws Exception {
        int limit = program.maximumReplans();
        if (limit < 0 || limit > 8) throw LabException.invalid("重规划次数必须在0～8内");
        budget.check(); var plan = Objects.requireNonNull(program.initialPlan());
        for (int revision = 0; ; revision++) {
            budget.check(); var result = program.executePlan(plan); budget.check();
            if (!program.needsReplan(plan, result)) return result;
            if (revision >= limit) throw new LabException("BUDGET_EXCEEDED", "计划重规划次数已耗尽");
            budget.check(); plan = Objects.requireNonNull(program.replan(plan, result));
        }
    }
}
