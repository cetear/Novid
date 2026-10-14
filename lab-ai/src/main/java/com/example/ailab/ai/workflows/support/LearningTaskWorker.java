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
    private TaskLeaseRenewal renewals;
    @org.springframework.beans.factory.annotation.Autowired
    public void renewals(TaskLeaseRenewal value) { this.renewals=value; }
    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor();
    public LearningTaskWorker(TaskStorePort tasks, com.example.ailab.ai.workflows.content.DocumentDrivenWorkflow workflow, TraceTelemetryPort telemetry) {
        this.tasks=tasks;this.workflow=workflow;this.telemetry=telemetry;
        this.renewals=new TaskLeaseRenewal(tasks);
    }
    public void run(TaskLease lease) { WorkerLogging.run(lease, runId -> run(lease,runId)); }
    private void run(TaskLease lease,String runId) {
        var observation=telemetry.open(runId,lease.actor().userId(),null,lease.task().taskId(),null);
        var renewal=heartbeat.scheduleWithFixedDelay(()-> {
            try { if(!renewals.renew(lease))LOG.warn("event=learning.lease_lost taskId={}",lease.task().taskId()); }
            catch(RuntimeException failure){LOG.warn("event=learning.renew_failed taskId={} code={}",lease.task().taskId(),com.example.ailab.contract.error.DiagnosticFailure.code(failure));}
        },20,20,TimeUnit.SECONDS);
        try(var span=observation.span("TASK","document_learning_workflow")) {
            try { workflow.learning(lease,span.context(),runId); }
            catch(LabException failure) {
                span.fail(failure);
                if(TaskLeaseRenewal.UNAVAILABLE.equals(failure.code()))LOG.warn("event=learning.deferred code={}",failure.code());
                else { fail(lease,failure.code());LOG.warn("event=learning.failed code={}",failure.code()); }
            }
            catch(Exception failure) {
                // 其他仓库在开启事务时也可能拿不到连接；退出本轮，不从头重放工作流。
                if(failure instanceof org.springframework.transaction.CannotCreateTransactionException&&TaskLeaseRenewal.acquisitionTimeout(failure)) {
                    span.fail(new LabException(TaskLeaseRenewal.UNAVAILABLE,"数据库暂不可用，等待租约恢复"));
                    LOG.warn("event=learning.deferred code={}",TaskLeaseRenewal.UNAVAILABLE,com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
                } else {
                    span.fail(failure);LOG.error("event=learning.failed code=TASK_FAILED",com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));fail(lease,"TASK_FAILED");
                }
            }
        } finally {renewal.cancel(true);observation.finish();}
    }
    private void fail(TaskLease lease,String code) {
        try { tasks.fail(lease,code); }
        catch(RuntimeException failure) {
            LOG.error("event=learning.failure_write_failed taskId={} originalCode={}",lease.task().taskId(),code,
                    com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
        }
    }
    @PreDestroy public void close(){heartbeat.shutdownNow();}
}
