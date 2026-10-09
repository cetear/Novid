package com.example.ailab.ai.orchestration;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.ExecutionArchitecture;

/** 架构执行接口：使用调用方的共享预算，不创建业务预算或决定工作流身份。 */
public interface ArchitectureExecutor {
    ExecutionArchitecture architecture();
    String version();
    boolean supports(WorkflowProgram<?> program);
    <R> R execute(WorkflowProgram<R> program, ExecutionBudget budget) throws Exception;
}
