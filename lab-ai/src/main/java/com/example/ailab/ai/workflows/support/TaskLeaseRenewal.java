package com.example.ailab.ai.workflows.support;

import com.example.ailab.contract.dto.TaskLease;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.TaskStorePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 事务代理外的有限续租恢复；只重试未取得连接的事务，不重放模型或业务写入。 */
@Component
public final class TaskLeaseRenewal {
    public static final String UNAVAILABLE="DATABASE_TEMPORARILY_UNAVAILABLE";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(TaskLeaseRenewal.class);
    private final TaskStorePort tasks;
    private final int attempts;
    private final long backoffMillis, windowMillis;
    public TaskLeaseRenewal(TaskStorePort tasks) { this(tasks,3,250,20000); }
    @Autowired
    public TaskLeaseRenewal(TaskStorePort tasks,
            @Value("${lab.task.renewal-attempts:3}") int attempts,
            @Value("${lab.task.renewal-backoff-ms:250}") long backoffMillis,
            @Value("${lab.task.renewal-window-ms:20000}") long windowMillis) {
        if(attempts<1||attempts>5||backoffMillis<1||backoffMillis>2000||windowMillis<1||windowMillis>30000)
            throw new IllegalArgumentException("续租恢复配置无效");
        this.tasks=tasks;this.attempts=attempts;this.backoffMillis=backoffMillis;this.windowMillis=windowMillis;
    }
    public boolean renew(TaskLease lease) { return renew(lease,Instant.MAX); }
    public boolean renew(TaskLease lease,Instant deadline) {
        // 同一次执行的心跳和前台核验共用lease，避免满池时叠加续租等待者。
        synchronized(lease) {
            long started=System.nanoTime();
            for(int attempt=1;attempt<=attempts;attempt++) {
                check(deadline);
                try {
                    boolean renewed=tasks.renew(lease);
                    check(deadline);
                    if(attempt>1)LOG.info("event=task.renew_recovered taskId={} attempt={} valid={}",lease.task().taskId(),attempt,renewed);
                    return renewed;
                } catch(CannotCreateTransactionException failure) {
                    if(!acquisitionTimeout(failure))throw failure;
                    LOG.warn("event=task.renew_unavailable taskId={} attempt={}",lease.task().taskId(),attempt);
                    long remaining=windowMillis-TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
                    if(attempt==attempts||remaining<=0) {
                        LOG.warn("event=task.renew_deferred taskId={}",lease.task().taskId(),
                                com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
                        throw new LabException(UNAVAILABLE,"数据库暂不可用，等待租约恢复");
                    }
                    check(deadline);
                    long delay=Math.min(remaining,backoffMillis*attempt);
                    if(!deadline.equals(Instant.MAX))delay=Math.min(delay,Math.max(1,java.time.Duration.between(Instant.now(),deadline).toMillis()));
                    try { Thread.sleep(delay); }
                    catch(InterruptedException stopped) {
                        Thread.currentThread().interrupt();throw new LabException("REQUEST_CANCELLED","续租等待已中断");
                    }
                    if(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)>=windowMillis)
                        throw new LabException(UNAVAILABLE,"数据库暂不可用，等待租约恢复");
                }
            }
            throw new IllegalStateException("续租恢复未返回");
        }
    }
    static boolean acquisitionTimeout(Throwable failure) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause())
            if(cause instanceof SQLTransientConnectionException)return true;
        return false;
    }
    private static void check(Instant deadline) {
        if(Thread.currentThread().isInterrupted())throw new LabException("REQUEST_CANCELLED","续租等待已中断");
        if(!Instant.now().isBefore(deadline))throw new LabException("BUDGET_EXCEEDED","续租等待超过任务期限");
    }
}
