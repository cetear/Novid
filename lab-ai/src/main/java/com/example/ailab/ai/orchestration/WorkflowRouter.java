package com.example.ailab.ai.orchestration;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 可信业务类型固定映射架构，不调用模型分类；新增版本只影响新任务。 */
@Component
public final class WorkflowRouter {
    public static final WorkflowExecutionBinding PPT_REACT = new WorkflowExecutionBinding(
            "notes-ppt", "1", ExecutionArchitecture.REACT, "ppt-react-v1");
    public static final WorkflowExecutionBinding PPT_LEGACY = new WorkflowExecutionBinding(
            "notes-ppt", "0", ExecutionArchitecture.PLAN_EXECUTE, "legacy-media-dag-v1");
    public static final WorkflowExecutionBinding QUIZ = new WorkflowExecutionBinding(
            "learning-quiz", "1", ExecutionArchitecture.FIXED, "quiz-fixed-v1");
    public static final WorkflowExecutionBinding COMPILATION = new WorkflowExecutionBinding(
            "knowledge-compilation", "1", ExecutionArchitecture.FIXED, "compilation-fixed-v1");
    private final Map<String, WorkflowExecutionBinding> routes = Map.of(
            "NOTES_PPT", PPT_REACT, "QUIZ_GENERATION", QUIZ, "KNOWLEDGE_COMPILATION", COMPILATION);

    public WorkflowExecutionBinding route(String taskType) {
        var route = routes.get(taskType);
        if (route == null) throw new LabException("WORKFLOW_NOT_REGISTERED", "此工作流尚未登记执行架构");
        return route;
    }

    public void verify(String taskType, WorkflowExecutionBinding saved) {
        if (!("NOTES_PPT".equals(taskType) && (PPT_REACT.equals(saved) || PPT_LEGACY.equals(saved)))
                && !(Learning.supports(taskType) && java.util.Objects.equals(routes.get(taskType), saved)))
            throw new LabException("WORKFLOW_EXECUTOR_UNAVAILABLE", "任务绑定的工作流版本或执行器不可用");
    }
}
