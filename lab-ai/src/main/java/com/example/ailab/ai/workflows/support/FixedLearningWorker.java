package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.model.ModelGateway;
import com.example.ailab.ai.orchestration.WorkflowRouter;
import com.example.ailab.ai.orchestration.support.WorkerLogging;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.skills.SkillCatalog;
import com.example.ailab.ai.tools.ToolExecutionService;
import com.example.ailab.ai.workflows.quiz.QuizWorkflow;
import com.example.ailab.ai.workflows.compilation.KnowledgeCompilationWorkflow;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.*;

/** 共用普通任务队列的固定学习流程；独立业务执行，旧报告Worker只负责分派。 */
@Component
public final class FixedLearningWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FixedLearningWorker.class);
    private final TaskStorePort tasks;
    private final FixedWorkflowStorePort store;
    private final WorkflowRunStorePort workflows;
    private final WorkflowRouter router;
    private final SkillCatalog skills;
    private final ToolExecutionService tools;
    private final KnowledgeCapabilityPort knowledge;
    private final ModelGateway model;
    private final TraceTelemetryPort telemetry;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    public FixedLearningWorker(TaskStorePort tasks, FixedWorkflowStorePort store, WorkflowRunStorePort workflows,
            WorkflowRouter router, SkillCatalog skills, ToolExecutionService tools, KnowledgeCapabilityPort knowledge,
            ModelGateway model, TraceTelemetryPort telemetry) {
        this.tasks = tasks; this.store = store; this.workflows = workflows; this.router = router; this.skills = skills;
        this.tools = tools; this.knowledge = knowledge; this.model = model; this.telemetry = telemetry;
    }
    public void run(TaskLease lease) { WorkerLogging.run(lease, runId -> run(lease, runId)); }
    private void run(TaskLease lease, String runId) {
        var observation = telemetry.open(runId, lease.actor().userId(), null, lease.task().taskId(), null);
        var renewal = heartbeat.scheduleAtFixedRate(() -> {
            try { tasks.renew(lease); } catch (RuntimeException failure) { LOG.warn("event=learning.renew_failed"); }
        }, 20, 20, TimeUnit.SECONDS);
        try (var span = observation.span("TASK", "fixed_learning_workflow")) {
            try {
                if (!Learning.supports(lease.request().taskType()) || !lease.request().strategy().equals("FIXED"))
                    throw new LabException("WORKFLOW_EXECUTOR_UNAVAILABLE", "固定学习工作流类型或执行策略不支持");
                var workflow = workflows.binding(lease).orElseGet(() -> {
                    var selected = router.route(lease.request().taskType()); workflows.bind(lease, selected); return selected;
                });
                router.verify(lease.request().taskType(), workflow);
                var reader = new LearningSourceReader(knowledge, tasks);
                tasks.beginStep(lease, "prepare");
                var baseline = store.baseline(lease).orElseGet(() -> {
                    var skill = skills.learningBinding(lease.request().taskType(), tools.contracts());
                    var prepared = reader.prepare(lease, workflow, skill); store.bind(lease, prepared); return prepared;
                });
                if (!baseline.workflow().equals(workflow) || !baseline.limits().equals(Learning.Limits.forType(lease.request().taskType()))
                        || !baseline.skill().actionPolicyVersion().equals("fixed-learning-v1"))
                    throw new LabException("WORKFLOW_EXECUTOR_UNAVAILABLE", "固定学习执行基线版本不兼容");
                SkillCatalog.verify(baseline.skill());
                Runnable verify = () -> { reader.verify(lease, baseline); tools.verifyContracts(baseline.skill().toolContracts()); };
                verify.run();
                var progress = lease.task().progress();
                long elapsed = progress == null ? 0 : progress.elapsedExecutionSeconds();
                var budget = new ExecutionBudget(Duration.ofSeconds(Math.max(1, 1200 - elapsed)), baseline.limits().attempts(),
                        () -> tasks.reserveModelAttempt(lease), () -> tasks.reserveModelTurn(lease), verify,
                        () -> tasks.reserveToolCall(lease), () -> tasks.reserveModelRepair(lease))
                        .learning(lease.request().taskType()).traced(span.context())
                        .fees(new FeeScope(lease.actor(), "TASK", Long.toString(lease.task().taskId()), runId));
                tasks.completePreparation(lease);
                var session = new LearningSession(lease, baseline, model, budget, store, reader, verify);
                session.extract();
                if (lease.request().taskType().equals("QUIZ_GENERATION")) new QuizWorkflow().run(session);
                else new KnowledgeCompilationWorkflow().run(session);
                LOG.info("event=learning.published workflowId={} executorVersion={}", workflow.workflowId(), workflow.executorVersion());
            } catch (LabException failure) {
                span.fail(failure); tasks.fail(lease, failure.code()); LOG.warn("event=learning.failed code={}", failure.code());
            } catch (Exception failure) {
                span.fail(failure); tasks.fail(lease, "TASK_FAILED"); LOG.error("event=learning.failed code=TASK_FAILED");
            }
        } finally { renewal.cancel(false); observation.finish(); }
    }
    @PreDestroy public void close() { heartbeat.shutdownNow(); }
}
