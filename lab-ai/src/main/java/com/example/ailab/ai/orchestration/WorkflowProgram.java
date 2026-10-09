package com.example.ailab.ai.orchestration;

import com.example.ailab.contract.dto.ExecutionArchitecture;

/** 工作流提供类型化执行程序；程序与已登记架构必须一致。 */
public interface WorkflowProgram<R> {
    ExecutionArchitecture architecture();
}
