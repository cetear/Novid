package com.example.ailab.ai.workflows.support;

import com.example.ailab.contract.dto.Learning;
import com.example.ailab.contract.port.TaskStorePort;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;
import java.util.concurrent.*;

/** 普通任务队列只分派已登记的学习工作流；模型、心跳和检查点由业务 Worker 管理。 */
@Component
@ConditionalOnProperty(name = "lab.task.worker-enabled", havingValue = "true")
public final class TaskQueueWorker {
    private final TaskStorePort tasks;
    private final FixedLearningWorker learning;
    private final String workerId = UUID.randomUUID().toString();
    private final ThreadPoolExecutor coordinator = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());

    public TaskQueueWorker(TaskStorePort tasks, FixedLearningWorker learning) {
        this.tasks = tasks;
        this.learning = learning;
    }

    @Scheduled(fixedDelay = 3000)
    public void scan() {
        if (coordinator.getActiveCount() > 0 || !coordinator.getQueue().isEmpty()) return;
        tasks.claim(workerId).ifPresent(lease -> coordinator.execute(() -> {
            if (Learning.supports(lease.request().taskType())) learning.run(lease);
            else tasks.fail(lease, "WORKFLOW_RETIRED");
        }));
    }

    @PreDestroy public void close() { coordinator.shutdownNow(); }
}
