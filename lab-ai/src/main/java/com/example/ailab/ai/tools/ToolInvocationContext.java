package com.example.ailab.ai.tools;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.ScopeRequest;

import java.util.function.Function;

/**
 * 工具共享原预算，知识检索的向量回调沿现有模型网关，不另建模型客户端。
 */
public record ToolInvocationContext(UserContext actor, ScopeRequest scope, String task, String callId,
                                    String evidenceId, ExecutionBudget budget, long deadlineNanos,
                                    Function<String, ToolExecutionService.ModelVector> vector) {
}
