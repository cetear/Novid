package com.example.ailab.web.controller;

import com.example.ailab.contract.error.LabException;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 在线 SSE 使用固定执行器；线程和等待队列均有上限，不随请求创建无界线程。 */
@Component
public class ChatStreamExecutor {
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), namedThreads("chat-stream-"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor heartbeats = new ScheduledThreadPoolExecutor(1,
            namedThreads("chat-heartbeat-"));

    /** 已取消的心跳立即从调度队列移除，避免完成会话长期保留引用。 */
    public ChatStreamExecutor() {
        heartbeats.setRemoveOnCancelPolicy(true);
    }

    /** 队列满时真实拒绝，不创建额外线程或暗中延长在线请求预算。 */
    public void execute(Runnable work) {
        try {
            workers.execute(withLogContext(work));
        } catch (RejectedExecutionException unavailable) {
            throw new LabException("RATE_LIMITED", "在线问答繁忙，请稍后重试");
        }
    }

    /** 两秒仅发送无正文处理事件，帮助容器发现断线并撤销尚未提交的执行权。 */
    public ScheduledFuture<?> heartbeat(Runnable pulse) {
        return heartbeats.scheduleAtFixedRate(withLogContext(pulse), 2, 2, TimeUnit.SECONDS);
    }

    /** 复用线程显式传递日志编号，并在完成后还原，避免串到下一位用户。 */
    private static Runnable withLogContext(Runnable work) {
        var captured = org.slf4j.MDC.getCopyOfContextMap();
        return () -> {
            var previous = org.slf4j.MDC.getCopyOfContextMap();
            if (captured == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(captured);
            try { work.run(); }
            finally {
                if (previous == null) org.slf4j.MDC.clear(); else org.slf4j.MDC.setContextMap(previous);
            }
        };
    }

    /** 应用退出释放本组件线程；远程模型取消仍由请求控制与供应商能力共同决定。 */
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        heartbeats.shutdownNow();
    }

    /** 只给固定池创建有名称的守护线程，便于识别执行器与生命周期。 */
    private static ThreadFactory namedThreads(String prefix) {
        var sequence = new AtomicInteger();
        return work -> {
            var thread = new Thread(work, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
