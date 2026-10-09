package com.example.ailab.ai.orchestration.multiagent;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 多角色依赖调度：仅就绪角色进入调用方线程池，业务输入、预算和结果持久化由工作流负责。
 * 固定流程和模型计划都可使用此协作机制，它本身不调用模型或决定执行顺序。
 */
public final class AgentDagExecutor<R> implements AutoCloseable {
    private final Executor executor;
    private final Map<String, CompletableFuture<R>> futures = new ConcurrentHashMap<>();
    private final Map<String, FutureTask<R>> tasks = new HashMap<>();
    private boolean closed;

    /** 复用工作流已有的有界线程池，不创建额外队列或执行预算。 */
    public AgentDagExecutor(Executor executor) {
        this.executor = java.util.Objects.requireNonNull(executor);
    }

    /** 节点必须按已验证拓扑登记；依赖完成前不占用角色线程。 */
    public void submit(String id, List<String> dependencies, Function<List<R>, R> action) {
        var inputs = List.copyOf(dependencies);
        var completion = new CompletableFuture<R>();
        CompletableFuture<?>[] waiting;
        synchronized (this) {
            if (closed) throw new IllegalStateException("角色调度已关闭");
            if (futures.containsKey(id)) throw new IllegalArgumentException("角色步骤ID重复");
            waiting = inputs.stream().map(this::require).toArray(CompletableFuture[]::new);
            futures.put(id, completion);
        }
        CompletableFuture.allOf(waiting).whenComplete((ignored, failure) -> {
            if (failure != null) {
                completion.completeExceptionally(failure);
                return;
            }
            // FutureTask保存真正执行线程，取消能够中断在途动作，而非仅取消汇合结果。
            var task = new FutureTask<R>(() -> action.apply(inputs.stream().map(this::result).toList())) {
                @Override protected void done() {
                    try {
                        if (isCancelled()) completion.cancel(false);
                        else completion.complete(get());
                    } catch (ExecutionException error) {
                        completion.completeExceptionally(error.getCause());
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        completion.completeExceptionally(error);
                    }
                }
            };
            synchronized (this) {
                if (closed) { completion.cancel(false); return; }
                tasks.put(id, task);
            }
            // 不持调度锁执行，兼容直接执行及调用方线程执行的拒绝策略。
            try { executor.execute(task); }
            catch (RuntimeException rejected) {
                completion.completeExceptionally(rejected);
                task.cancel(false);
            }
        });
    }

    /** 读取已完成祖先结果；工作流负责保证非直接依赖也是当前节点的祖先。 */
    public R result(String id) {
        return require(id).join();
    }

    /** 等待全部节点，沿用调用方共同截止，不给每个角色重新分配等待时间。 */
    public Map<String, R> await(Instant deadline) throws Exception {
        CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new))
                .get(Math.max(1, Duration.between(Instant.now(), deadline).toMillis()), TimeUnit.MILLISECONDS);
        var results = new HashMap<String, R>();
        futures.forEach((id, future) -> results.put(id, future.join()));
        return results;
    }

    private CompletableFuture<R> require(String id) {
        var future = futures.get(id);
        if (future == null) throw new IllegalArgumentException("角色依赖尚未登记");
        return future;
    }

    /** 关闭后不再启动角色，并中断正在执行的动作；线程池生命周期仍归工作流所有。 */
    @Override
    public void close() {
        List<FutureTask<R>> running;
        synchronized (this) {
            if (closed) return;
            closed = true;
            running = List.copyOf(tasks.values());
        }
        futures.values().forEach(future -> future.cancel(false));
        running.forEach(task -> task.cancel(true));
    }
}
