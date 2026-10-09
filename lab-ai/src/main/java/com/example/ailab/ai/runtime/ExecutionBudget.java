package com.example.ailab.ai.runtime;

import com.example.ailab.contract.error.LabException;

import java.time.*;

/**
 * 同请求所有角色共享的原子预算，主备不会重置额度。
 */
public final class ExecutionBudget {
    private final Instant deadline;
    private final int maxAttempts;
    private final Runnable journal;
    private final Runnable turnJournal;
    private final Runnable cancellationCheck;
    private int attempts, turns, tools, repairs;
    private int maxTurns = 6, maxTools = 8;
    private int maxRepairs = 1;
    private int maximumWaitSeconds = 30;

    /**
     * 仅媒体入口显式启用独立预算，普通问答／报告仍六轮八工具。
     */
    public ExecutionBudget media(String taskType) {
        if (!"NOTES_VIDEO".equals(taskType) || maxAttempts != 36)
            throw new IllegalArgumentException("媒体预算仅限登记媒体任务");
        maxTurns = 24;
        maxTools = 24;
        maximumWaitSeconds = 120;
        return this;
    }

    /** 固定学习工作流共享持久额度，恢复不获得新预算。 */


    private Runnable toolJournal = () -> {
    }, repairJournal = () -> {
    };
    private final java.util.List<com.example.ailab.contract.dto.ModelRoute.Attempt> observed = new java.util.ArrayList<>();
    private final ThreadLocal<Integer> observedIndex = new ThreadLocal<>();
    public ExecutionBudget content(com.example.ailab.contract.dto.ContentWorkflow.Policy policy) {
        if (maxAttempts != policy.attempts()) throw new IllegalArgumentException("资料工作流预算不匹配");
        maxTurns = policy.turns(); maxTools = policy.tools(); maximumWaitSeconds = 120;
        maxRepairs = policy.turns();
        return this;
    }
    private com.example.ailab.contract.context.TraceContext rootTrace = com.example.ailab.contract.context.TraceContext.disabled("none");
    private final ThreadLocal<com.example.ailab.contract.context.TraceContext> activeTrace = new ThreadLocal<>();
    private final ThreadLocal<String> lastModelNode = new ThreadLocal<>();
    private com.example.ailab.contract.dto.FeeScope feeScope;

    /**
     * 费用关联独立于可丢追踪；同一预算的并行角色共享持久scope，不可重新绑定清零。
     */
    public synchronized ExecutionBudget fees(com.example.ailab.contract.dto.FeeScope scope) {
        if (feeScope != null && !feeScope.equals(scope)) throw new IllegalStateException("不能更改费用归属");
        feeScope = java.util.Objects.requireNonNull(scope);
        return this;
    }

    /**
     * 提供服务端绑定的身份和稳定资源；模型输出和HTTP字段不能覆盖。
     */
    public synchronized com.example.ailab.contract.dto.FeeScope feeScope() {
        return feeScope;
    }

    /**
     * 工具申请关联本工作线程刚完成的实际模型节点，不混淆并行角色。
     */
    public void lastModelNode(String id) {
        lastModelNode.set(id);
    }

    /**
     * 没有模型节点时不补造调用；截断节点标识由图显示缺失。
     */
    public String lastModelNode() {
        return lastModelNode.get();
    }

    /**
     * 同预算绑定根追踪；观测上下文不会创建、复制或重置可靠额度。
     */
    public ExecutionBudget traced(com.example.ailab.contract.context.TraceContext trace) {
        rootTrace = trace;
        return this;
    }

    /**
     * 显式角色上下文优先于根，避免并行角色互相覆盖父节点。
     */
    public com.example.ailab.contract.context.TraceContext trace() {
        var t = activeTrace.get();
        return t == null ? rootTrace : t;
    }

    /**
     * 每个异步入口显式激活不可变父上下文，退出时恢复线程原状态。
     */
    public TraceActivation activate(com.example.ailab.contract.context.TraceContext trace) {
        var old = activeTrace.get();
        activeTrace.set(trace);
        return new TraceActivation(old);
    }

    public final class TraceActivation implements AutoCloseable {
        private final com.example.ailab.contract.context.TraceContext previous;

        /**
         * 保存工作线程自己的旧上下文，不能继承另一个角色。
         */
        private TraceActivation(com.example.ailab.contract.context.TraceContext previous) {
            this.previous = previous;
        }

        /**
         * 清理线程池上下文，不影响共享预算或其他工作线程。
         */
        public void close() {
            if (previous == null) activeTrace.remove();
            else activeTrace.set(previous);
        }
    }

    /**
     * 为在线请求或有限后台入库创建独立预算。
     */
    public ExecutionBudget(Duration duration, int maxAttempts) {
        this(duration, maxAttempts, () -> {
        });
    }

    /**
     * 后台任务在真实调用前可靠持久化 attempts。
     */
    public ExecutionBudget(Duration duration, int maxAttempts, Runnable journal) {
        this(duration, maxAttempts, journal, () -> {
        });
    }

    /**
     * 持久尝试与持久逻辑轮数采用独立原子端口。
     */
    public ExecutionBudget(Duration duration, int maxAttempts, Runnable journal, Runnable turnJournal) {
        this(duration, maxAttempts, journal, turnJournal, () -> {
        });
    }

    /**
     * 在线断线信号与原有时间／尝试预算共用步骤检查，不改变后台任务预算语义。
     */
    public ExecutionBudget(Duration duration, int maxAttempts, Runnable journal, Runnable turnJournal, Runnable cancellationCheck) {
        deadline = Instant.now().plus(duration);
        this.maxAttempts = maxAttempts;
        this.journal = journal;
        this.turnJournal = turnJournal;
        this.cancellationCheck = cancellationCheck;
    }

    /**
     * 后台所有角色共享持久工具／修复预算，恢复不会重新获得八次或一次额度。
     */
    public ExecutionBudget(Duration duration, int maxAttempts, Runnable journal, Runnable turnJournal,
                           Runnable cancellationCheck, Runnable toolJournal, Runnable repairJournal) {
        this(duration, maxAttempts, journal, turnJournal, cancellationCheck);
        this.toolJournal = toolJournal;
        this.repairJournal = repairJournal;
    }

    /**
     * 真实尝试开始前消费额度，失败也不能视为免费。
     */
    public synchronized void attempt() {
        check();
        if (attempts >= maxAttempts) throw new LabException("BUDGET_EXCEEDED", "模型尝试预算耗尽");
        journal.run();
        attempts++;
    }

    /**
     * 逻辑模型调用与失败重试计数分开。
     */
    public synchronized void turn() {
        check();
        if (turns >= maxTurns) throw new LabException("BUDGET_EXCEEDED", "模型轮数超过限制");
        turnJournal.run();
        turns++;
    }

    /**
     * 工具调用计入共享限额。
     */
    public synchronized void tool() {
        check();
        if (tools >= maxTools) throw new LabException("BUDGET_EXCEEDED", "工具次数超过限制");
        toolJournal.run();
        tools++;
    }

    /**
     * 在线截止时间不能因重试而延长。
     */
    public void check() {
        if (Thread.currentThread().isInterrupted()) throw new LabException("REQUEST_CANCELLED", "执行线程已取消");
        cancellationCheck.run();
        if (!Instant.now().isBefore(deadline)) throw new LabException("BUDGET_EXCEEDED", "执行已超时");
    }

    /**
     * 在线单次等待最多30秒，登记媒体最多120秒；均不能超过原任务剩余期限。
     */
    public Duration timeout() {
        check();
        return Duration.ofMillis(Math.max(1, Math.min(maximumWaitSeconds * 1000L, Duration.between(Instant.now(), deadline).toMillis())));
    }

    /**
     * 汇报包含失败和备用的真实尝试。
     */
    public synchronized int attempts() {
        return attempts;
    }

    /**
     * 全请求最多一次结构修复；修复还需另消耗逻辑轮与真实尝试，不重建期限。
     */
    public synchronized void repair() {
        check();
        if (repairs >= maxRepairs) throw new LabException("MODEL_REPAIR_EXHAUSTED", "结构化修复额度耗尽");
        repairJournal.run();
        repairs++;
    }

    /**
     * 运行期间保留所有聊天失败／修复用量，后续持久追踪可读取；不依赖可丢trace。
     */
    public synchronized void observe(com.example.ailab.contract.dto.ModelRoute.Attempt attempt) {
        observed.add(attempt);
        observedIndex.set(observed.size() - 1);
    }

    /**
     * 失败请求也可读取用量来源，未知不得当免费；当前尚未持久保存这些快照。
     */
    public synchronized java.util.List<com.example.ailab.contract.dto.ModelRoute.Attempt> observedAttempts() {
        return java.util.List.copyOf(observed);
    }

    /**
     * 字段校验晚于提供方响应，保留已知用量但更正最后一次结果。
     */
    public synchronized void invalidStructure() {
        Integer index = observedIndex.get();
        if (index == null) return;
        var a = observed.get(index);
        observed.set(index, new com.example.ailab.contract.dto.ModelRoute.Attempt(a.modelId(), "MODEL_STRUCTURED_INVALID", a.reservedInputTokens(),
                a.countSource(), a.inputTokens(), a.outputTokens(), a.usageSource(), a.priceRef()));
    }

    /**
     * 将同一在线截止时间传给短提交，等待数据库锁不能延长模型步骤预算。
     */
    public Instant deadline() {
        return deadline;
    }
}
