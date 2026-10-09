package com.example.ailab.ai.orchestration.planexecute;

import com.example.ailab.ai.orchestration.WorkflowProgram;
import com.example.ailab.contract.dto.ExecutionArchitecture;

/** 业务负责类型化计划验证、检查点和重规划额度预留；架构负责执行循环。 */
public interface PlanExecuteProgram<P, R> extends WorkflowProgram<R> {
    P initialPlan();
    R executePlan(P plan) throws Exception;
    boolean needsReplan(P plan, R result);
    P replan(P plan, R result);
    int maximumReplans();
    default ExecutionArchitecture architecture() { return ExecutionArchitecture.PLAN_EXECUTE; }
}
