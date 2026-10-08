package com.example.ailab.ai.orchestration.worker;

import com.example.ailab.contract.dto.TaskLease;
import com.example.ailab.contract.error.DiagnosticFailure;
import org.slf4j.*;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 队列运行创建独立日志上下文，结束归还线程时恢复原上下文。
 */
public final class WorkerLogging {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerLogging.class);

    private WorkerLogging() {
    }

    public static void run(TaskLease lease, Consumer<String> action) {
        var previous = MDC.getCopyOfContextMap();
        String id = UUID.randomUUID().toString();
        MDC.clear();
        MDC.put("operationId", id);
        MDC.put("traceId", id);
        MDC.put("taskId", Long.toString(lease.task().taskId()));
        MDC.put("actorId", Long.toString(lease.actor().userId()));
        long started = System.nanoTime();
        LOG.info("event=worker.start");
        try {
            action.accept(id);
        } catch (RuntimeException failure) {
            LOG.error("event=worker.unhandled code={}", DiagnosticFailure.code(failure), DiagnosticFailure.sanitized(failure));
            throw failure;
        } finally {
            // finished只表示本次执行退出，业务结果由publish/review_pending/failed事实给出。
            LOG.info("event=worker.finished durationMs={}", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
