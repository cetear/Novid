package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.orchestration.support.WorkerLogging;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/** 学习任务队列的执行、心跳和失败处理。 */
@Component
public final class LearningTaskWorker {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(LearningTaskWorker.class);
    private final TaskStorePort tasks;
    private final com.example.ailab.ai.workflows.content.DocumentDrivenWorkflow workflow;
    private final TraceTelemetryPort telemetry;
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    public LearningTaskWorker(TaskStorePort tasks, com.example.ailab.ai.workflows.content.DocumentDrivenWorkflow workflow, TraceTelemetryPort telemetry) {
        this.tasks=tasks;this.workflow=workflow;this.telemetry=telemetry;
    }
    public void run(TaskLease lease) { WorkerLogging.run(lease, runId -> run(lease,runId)); }
    private void run(TaskLease lease,String runId) {
        var observation=telemetry.open(runId,lease.actor().userId(),null,lease.task().taskId(),null);
        var renewal=heartbeat.scheduleAtFixedRate(()-> {try{tasks.renew(lease);}catch(RuntimeException failure){LOG.warn("event=learning.renew_failed");}},20,20,TimeUnit.SECONDS);
        try(var span=observation.span("TASK","document_learning_workflow")) {
            try { workflow.learning(lease,span.context(),runId); }
            catch(LabException failure) {span.fail(failure);tasks.fail(lease,failure.code());LOG.warn("event=learning.failed code={}",failure.code());}
            catch(Exception failure) {span.fail(failure);LOG.error("event=learning.failed code=TASK_FAILED",com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));tasks.fail(lease,"TASK_FAILED");}
        } finally {renewal.cancel(false);observation.finish();}
    }
    @PreDestroy public void close(){heartbeat.shutdownNow();}
}
