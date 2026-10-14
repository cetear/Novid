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
    private final int maximum, days, capacity;
    private final ArrayBlockingQueue<Batch> queue;
    private final ConcurrentMap<String, LiveRun> live = new ConcurrentHashMap<>();
    private final Thread worker;
    private final OtlpTraceExporter exporter;
    private volatile boolean running = true;
    private volatile boolean writing;
    private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long progressIntervalMillis=1000;
    @org.springframework.beans.factory.annotation.Autowired
    public void progressInterval(@Value("${lab.observability.progress-interval-ms:1000}") long millis) {
        if(millis<100||millis>10000)throw new IllegalArgumentException("观测合并周期配置无效");
        progressIntervalMillis=millis;
    }

    private record Batch(TraceSnapshot run, List<TraceNode> nodes, TraceContext context) {
    }
    private static final class LiveRun {
        final TraceSnapshot run;
        final TraceContext context;
        volatile long writtenRevision;
        long nextWriteNanos;
        LiveRun(TraceSnapshot run, TraceContext context) { this.run = run; this.context = context; }
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
        this.capacity = capacity;
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
        var initial = new TraceSnapshot(id, actor, "RUNNING", "none", 0, false, start, null, session, task, ingestion, prior, true, false, 0);
        var holder = new TraceContext[1];
        var context = new TraceContext(maximum, (nodes, incomplete) -> {
            var run = snapshotRun(initial, nodes, true, incomplete);
            pending.incrementAndGet();
            live.remove(id);
            // 终态先标等待写入；队列丢失不能让数据库伪装为完整成功图。
            try {
                store.record(mark(run, true, false, 0));
            } catch (RuntimeException ignored) {
                holder[0].drop();
            }
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
            store.record(initial);
        } catch (RuntimeException ignored) {
            failed = true;
        }
        if (failed) {
            context.drop();
            LOG.warn("event=trace.open_failed traceId={}", id);
        }
        // 活跃运行有界登记；节点变化由后台合并，不在业务线程逐节点写库。
        synchronized (live) {
            if (running && live.size() < capacity) live.put(id, new LiveRun(initial, context));
            else {
                context.drop();
                LOG.warn("event=trace.live_dropped traceId={}", id);
            }
        }
        return context;
    }

    private TraceSnapshot snapshotRun(TraceSnapshot base, List<TraceNode> nodes, boolean terminal, boolean incomplete) {
        var root = nodes.stream().filter(n -> n.parentSpanId() == null).findFirst().orElse(null);
        var models = nodes.stream().filter(n -> n.type().equals("MODEL") || n.type().equals("EMBEDDING")).toList();
        String status = !terminal ? "RUNNING" : root == null ? "FAILED" : root.endedAt() == null ? "INTERRUPTED" : root.status();
        String model = models.isEmpty() ? "none" : models.get(models.size() - 1).modelId();
        return new TraceSnapshot(base.traceId(), base.actorUserId(), status, model == null ? "none" : model,
                models.size(), models.stream().anyMatch(n -> "SIMULATED".equals(n.usageSource())), base.createdAt(),
                terminal ? Instant.now() : null, base.sessionId(), base.taskId(), base.ingestionId(), base.previousTraceId(),
                !terminal || incomplete, incomplete, nodes.size());
    }

    /**
     * 独立串行写入，最多一次重试；导出先脱敏，外部失效只影响观测完整性。
     */
    private void drain() {
        while (running || !queue.isEmpty()) {
            try {
                var batch = queue.poll(100, TimeUnit.MILLISECONDS);
                if (batch != null) {
                    writing = true;
                    try { write(batch, true); }
                    finally { pending.decrementAndGet(); }
                }
                if (running) {
                    for (var active : live.values()) {
                        if (active.context.revision() == active.writtenRevision || System.nanoTime()<active.nextWriteNanos) continue;
                        writing = true;
                        var snapshot = active.context.snapshot();
                        if (!snapshot.finished()) {
                            active.nextWriteNanos=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(progressIntervalMillis);
                            write(new Batch(snapshotRun(active.run, snapshot.nodes(), false, snapshot.incomplete()), snapshot.nodes(), active.context), false);
                            active.writtenRevision = snapshot.revision();
                        }
                    }
                }
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                writing = false;
            }
        }
    }

    /** 运行中只持久化，外部导出仅发送一次终态；故障只影响观测完整性。 */
    private void write(Batch batch, boolean terminal) {
        boolean saved = false;
        for (int attempt = 0; attempt < 2 && !saved; attempt++) {
            try {
                store.recordGraph(mark(batch.run(), batch.run().incomplete() || batch.context().incomplete(), batch.context().incomplete(), batch.nodes().size()), batch.nodes());
                saved = true;
            } catch (RuntimeException failure) {
                LOG.warn("event=trace.write_failed traceId={} attempt={} code={}", batch.run().traceId(), attempt + 1, com.example.ailab.contract.error.DiagnosticFailure.code(failure));
                if (attempt == 1) batch.context().drop();
            }
        }
        boolean exportFailed = saved && terminal && !exporter.export(batch.run(), batch.nodes());
        if (exportFailed) LOG.warn("event=trace.export_failed traceId={}", batch.run().traceId());
        if (!saved || exportFailed) {
            batch.context().drop();
            try { store.record(mark(batch.run(), true, true, saved ? batch.nodes().size() : 0)); }
            catch (RuntimeException ignored) { }
        }
    }

    private boolean settled() {
        return pending.get() == 0 && !writing && (!running || live.values().stream().allMatch(r -> r.context.revision() == r.writtenRevision));
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
        while (!settled() && System.nanoTime() < end) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return settled();
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
