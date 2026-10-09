package com.example.ailab.ai.orchestration;

import com.example.ailab.contract.dto.WorkflowExecutionBinding;
import java.util.Map;

/** 新工作流通过 Spring 实现本接口登记路由，无需修改路由器。 */
public interface WorkflowModule {
    Map<String, WorkflowExecutionBinding> routes();
}
