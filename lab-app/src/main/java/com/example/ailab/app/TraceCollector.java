package com.example.ailab.app;

import com.example.ailab.contract.context.TraceContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * 正式入口唯一追踪收集器；有界队列与有限flush不参与可靠执行预算。
 */
@Component
public final class TraceCollector implements TraceTelemetryPort, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TraceCollector.class);
    private final TraceRecordPort store;
    private final int maximum, days;
    private final ArrayBlockingQueue<Batch> queue;
    private final Thread worker;
    private final OtlpTraceExporter exporter;
    private volatile boolean running = true;
    private volatile boolean writing;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();

    private record Batch(TraceSnapshot run, List<TraceNode> nodes, TraceContext context) {
    }

    /**
     * 限额启动校验；外部导出默认关闭，凭证只由Spring环境读取。
     */
    public TraceCollector(TraceRecordPort store,
                          @Value("${lab.observability.max-nodes:2000}") int maximum,
                          @Value("${lab.observability.queue-capacity:32}") int capacity,
                          @Value("${lab.observability.retention-days:7}") int days,
                          @Value("${lab.observability.export-enabled:false}") boolean export,
                          @Value("${lab.observability.otlp-endpoint:}") String endpoint,
                          @Value("${lab.observability.otlp-authorization:}") String authorization) {
        if (maximum < 1 || maximum > 2000 || capacity < 1 || capacity > 128 || days < 7 || days > 365)
            throw new IllegalArgumentException("观测限额配置无效");
        this.store = store;
        this.maximum = maximum;
        this.days = days;
        queue = new ArrayBlockingQueue<>(capacity);
        exporter = new OtlpTraceExporter(export, endpoint, authorization);
        worker = new Thread(this::drain, "trace-writer");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * 开始先保存不完整运行，进程崩溃也保留缺失事实；写失败不阻断业务。
     */
    public TraceContext open(String id, long actor, Long session, Long task, Long ingestion) {
        Instant start = Instant.now();
        String previous = null;
        boolean failed = false;
        try {
            previous = store.previous(actor, task, ingestion);
        } catch (RuntimeException ignored) {
            failed = true;
        }
        final String prior = previous;
        var holder = new TraceContext[1];
        var context = new TraceContext(maximum, (nodes, incomplete) -> {
            var root = nodes.stream().filter(n -> n.parentSpanId() == null).findFirst().orElse(null);
            var models = nodes.stream().filter(n -> n.type().equals("MODEL") || n.type().equals("EMBEDDING")).toList();
            String status = root == null ? "FAILED" : root.endedAt() == null ? "INTERRUPTED" : root.status();
            String model = models.isEmpty() ? "none" : models.get(models.size() - 1).modelId();
            var run = new TraceSnapshot(id, actor, status, model == null ? "none" : model, models.size(), models.stream().anyMatch(n -> "SIMULATED".equals(n.usageSource())), start, Instant.now(), session, task, ingestion, prior, incomplete, incomplete, nodes.size());
            // 终态先标等待写入；队列丢失不能让数据库伪装为完整成功图。
            try {
                store.record(mark(run, true, false, 0));
            } catch (RuntimeException ignored) {
                holder[0].drop();
            }
            pending.incrementAndGet();
            if (!running || !queue.offer(new Batch(run, nodes, holder[0]))) {
                LOG.warn("event=trace.queue_dropped traceId={}", id);
                pending.decrementAndGet();
                holder[0].drop();
                try {
                    store.record(mark(run, true, true, 0));
                } catch (RuntimeException ignored) {
                }
            }
        });
        holder[0] = context;
        try {
            store.record(new TraceSnapshot(id, actor, "RUNNING", "none", 0, false, start, null, session, task, ingestion, prior, true, false, 0));
        } catch (RuntimeException ignored) {
            failed = true;
        }
        if (failed) {
            context.drop();
            LOG.warn("event=trace.open_failed traceId={}", id);
        }
        return context;
    }

    /**
     * 独立串行写入，最多一次重试；导出先脱敏，外部失效只影响观测完整性。
     */
    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                var batch = queue.poll(200, TimeUnit.MILLISECONDS);
                if (batch == null) continue;
                writing = true;
                boolean saved = false;
                try {
                    for (int attempt = 0; attempt < 2 && !saved; attempt++) {
                        try {
                            store.recordGraph(mark(batch.run(), batch.run().incomplete() || batch.context().incomplete(), batch.context().incomplete(), batch.nodes().size()), batch.nodes());
                            saved = true;
                        } catch (RuntimeException ignored) {
                            LOG.warn("event=trace.write_failed traceId={} attempt={} code={}", batch.run().traceId(), attempt + 1, com.example.ailab.contract.error.DiagnosticFailure.code(ignored));
                            if (attempt == 1) batch.context().drop();
                        }
                    }
                    if (!saved) {
                        try {
                            store.record(mark(batch.run(), true, true, 0));
                        } catch (RuntimeException ignored) {
                        }
                    }
                    if (saved && !exporter.export(batch.run(), batch.nodes())) {
                        LOG.warn("event=trace.export_failed traceId={}", batch.run().traceId());
                        batch.context().drop();
                        try {
                            store.record(mark(batch.run(), true, true, batch.nodes().size()));
                        } catch (RuntimeException ignored) {
                        }
                    }
                } finally {
                    pending.decrementAndGet();
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                writing = false;
            }
        }
    }

    /**
     * 保留期只清理观测，每小时最多100行，失败静默等待下次调度。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000, initialDelay = 3600000)
    public void purge() {
        try {
            store.purge(Instant.now().minus(java.time.Duration.ofDays(days)), 100);
        } catch (RuntimeException failure) {
            LOG.error("event=trace.purge_failed", com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
        }
    }

    /**
     * 保留所有关联字段，只改变观测状态，不能更改任务成功或可靠attempt。
     */
    private TraceSnapshot mark(TraceSnapshot r, boolean incomplete, boolean dropped, int count) {
        return new TraceSnapshot(r.traceId(), r.actorUserId(), r.status(), r.modelId(), r.attempts(), r.mock(), r.createdAt(), r.endedAt(), r.sessionId(), r.taskId(), r.ingestionId(), r.previousTraceId(), incomplete, dropped, count);
    }

    /**
     * CLI和测试最多等待给定期限，队列不可用时不无限挂住进程。
     */
    public boolean flush(long milliseconds) {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(milliseconds, 5000)));
        while (pending.get() > 0 && System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return pending.get() == 0;
    }

    /**
     * 应用关闭有限等待；未flush成功的控制行继续显示不完整。
     */
    @jakarta.annotation.PreDestroy
    public void close() {
        running = false;
        flush(2000);
        worker.interrupt();
    }
}
