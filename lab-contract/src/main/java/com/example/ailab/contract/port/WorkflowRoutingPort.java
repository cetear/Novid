package com.example.ailab.contract.port;

import com.example.ailab.contract.dto.WorkflowExecutionBinding;

/** 创建任务时取得经过校验的服务端架构绑定，数据层不依赖 AI 执行实现。 */
public interface WorkflowRoutingPort {
    WorkflowExecutionBinding route(String taskType);
}
