package com.example.ailab.ai.orchestration.react;

import com.example.ailab.ai.model.*;
import com.example.ailab.ai.orchestration.WorkflowProgram;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.*;

/** ReAct 的模型输入、工具白名单及授权复核；不授予任意工具执行权限。 */
public record ReActProgram(UserContext actor, ScopeRequest scope, ModelRegistry.Selection selection, ModelInput input,
        Runnable verify, String toolTask, int maximumRounds, Set<String> allowedTools, Map<String, String> contracts)
        implements WorkflowProgram<BoundedToolLoop.Result> {
    public ReActProgram {
        Objects.requireNonNull(actor); Objects.requireNonNull(scope); Objects.requireNonNull(selection);
        Objects.requireNonNull(input); Objects.requireNonNull(verify); Objects.requireNonNull(toolTask);
        allowedTools = Set.copyOf(allowedTools); contracts = Map.copyOf(contracts);
        if (maximumRounds < 1 || maximumRounds > 64) throw new IllegalArgumentException("ReAct轮数必须在1～64内");
        if (!contracts.keySet().containsAll(allowedTools)) throw new IllegalArgumentException("ReAct工具必须提供绑定契约");
    }
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.REACT; }
}
