package com.example.ailab.ai.orchestration.fixed;

import com.example.ailab.ai.orchestration.*;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

/** 固定顺序执行，与模型决策循环、计划重规划和角色依赖调度分别实现。 */
@Component
public final class FixedWorkflowExecutor implements ArchitectureExecutor {
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.FIXED; }
    public String version() { return "fixed-v1"; }
    public boolean supports(WorkflowProgram<?> program) { return program instanceof FixedWorkflowProgram<?>; }
    public <R> R execute(WorkflowProgram<R> program, ExecutionBudget budget) throws Exception {
        if (!(program instanceof FixedWorkflowProgram<R> fixed)) throw new LabException("WORKFLOW_ARCHITECTURE_MISMATCH", "需要固定流程程序");
        for (var step : fixed.steps()) { budget.check(); step.action().call(); }
        budget.check(); var result = fixed.result().call(); budget.check(); return result;
    }
}
