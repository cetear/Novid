package com.example.ailab.ai.orchestration;

import com.example.ailab.contract.dto.*;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 业务工作流与架构的声明式绑定，独立于架构调度实现。 */
@Component
public final class BuiltInWorkflows implements WorkflowModule {
    public Map<String, WorkflowExecutionBinding> routes() {
        return Map.of(
                "QUIZ_GENERATION", new WorkflowExecutionBinding("learning-quiz", "2", ExecutionArchitecture.FIXED, "fixed-v1"),
                "KNOWLEDGE_COMPILATION", new WorkflowExecutionBinding("knowledge-compilation", "2", ExecutionArchitecture.FIXED, "fixed-v1"),
                "NOTES_PPT", new WorkflowExecutionBinding("notes-ppt", "2", ExecutionArchitecture.FIXED, "fixed-v1"),
                "NOTES_VIDEO", new WorkflowExecutionBinding("notes-video", "1", ExecutionArchitecture.PLAN_EXECUTE, "plan-execute-v1"));
    }
}
