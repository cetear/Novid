package com.example.ailab.ai.model;

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
    private int attempts, turns, tools;

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
        this(duration, maxAttempts, journal, turnJournal, () -> {});
    }

    /** 在线断线信号与原有时间／尝试预算共用步骤检查，不改变后台任务预算语义。 */
    public ExecutionBudget(Duration duration, int maxAttempts, Runnable journal, Runnable turnJournal, Runnable cancellationCheck) {
        deadline = Instant.now().plus(duration);
        this.maxAttempts = maxAttempts;
        this.journal = journal;
        this.turnJournal = turnJournal;
        this.cancellationCheck = cancellationCheck;
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
        if (++turns > 6) throw new LabException("BUDGET_EXCEEDED", "模型轮数超过限制");
        turnJournal.run();
    }

    /**
     * 工具调用计入共享限额。
     */
    public synchronized void tool() {
        check();
        if (++tools > 8) throw new LabException("BUDGET_EXCEEDED", "工具次数超过限制");
    }

    /**
     * 在线截止时间不能因重试而延长。
     */
    public void check() {
        cancellationCheck.run();
        if (!Instant.now().isBefore(deadline)) throw new LabException("BUDGET_EXCEEDED", "执行已超时");
    }

    /**
     * 实际调用超时取 30 秒和剩余期限的较小值。
     */
    public Duration timeout() {
        check();
        return Duration.ofMillis(Math.max(1, Math.min(30000, Duration.between(Instant.now(), deadline).toMillis())));
    }

    /**
     * 汇报包含失败和备用的真实尝试。
     */
    public synchronized int attempts() {
        return attempts;
    }

    /** 将同一在线截止时间传给短提交，等待数据库锁不能延长模型步骤预算。 */
    public Instant deadline() { return deadline; }
}
