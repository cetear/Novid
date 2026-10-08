package com.example.ailab.contract.dto;

/** 与任务固定的执行基线，恢复时不读取最新路由覆盖它。 */
public record WorkflowExecutionBinding(String workflowId, String workflowVersion,
                                       ExecutionArchitecture architecture, String executorVersion) {
    public WorkflowExecutionBinding {
        if (workflowId == null || workflowVersion == null || architecture == null || executorVersion == null
                || !workflowId.matches("[a-z][a-z0-9-]{0,63}") || !workflowVersion.matches("[a-zA-Z0-9.-]{1,32}")
                || !executorVersion.matches("[a-zA-Z0-9.-]{1,64}"))
            throw new IllegalArgumentException("工作流执行基线无效");
    }
}
