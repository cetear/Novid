package com.example.ailab.ai.orchestration.react;

import com.example.ailab.ai.model.ModelGateway;
import com.example.ailab.ai.orchestration.*;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.tools.ToolExecutionService;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

/** 独立的模型决策／工具反馈执行器，复用已验证的消息配对、工具授权和共享预算。 */
@Component
public final class ReActExecutor implements ArchitectureExecutor {
    private final BoundedToolLoop loop;
    public ReActExecutor(ModelGateway models, ToolExecutionService tools) { loop = new BoundedToolLoop(models, tools); }
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.REACT; }
    public String version() { return "react-v1"; }
    public boolean supports(WorkflowProgram<?> program) { return program instanceof ReActProgram; }
    @SuppressWarnings("unchecked")
    public <R> R execute(WorkflowProgram<R> program, ExecutionBudget budget) {
        if (!(program instanceof ReActProgram react)) throw new LabException("WORKFLOW_ARCHITECTURE_MISMATCH", "需要ReAct程序");
        budget.check();
        var result = loop.run(react.actor(), react.scope(), react.selection(), react.input(), budget, react.verify(),
                react.toolTask(), react.maximumRounds(), react.allowedTools(), react.contracts());
        budget.check(); return (R) result;
    }
}
