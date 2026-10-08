package com.example.ailab.ai.model;

import java.time.*;
import java.util.*;

/**
 * 进程内轻量健康状态；半开只由一个真实请求试探，不做后台轮询。
 */
public final class ModelHealthTracker {
    private static final class State {
        int failures;
        Instant until = Instant.MIN;
        boolean probe;
        long epoch;
    }

    public record Permit(String id, long epoch, boolean probe) {
    }

    private final Map<String, State> states = new HashMap<>();
    private final Map<String, Instant> quotas = new HashMap<>();
    private final Clock clock;
    private final ModelProperties.Failover policy;

    /**
     * 时钟可在确定性并发测试中推进，正式使用UTC。
     */
    public ModelHealthTracker(ModelProperties.Failover policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * 配额冷却和单探针在同一锁内检查；未取得许可不消费调用次数。
     */
    public synchronized Permit acquire(String id, String quota) {
        var now = clock.instant();
        if (quotas.getOrDefault(quota, Instant.MIN).isAfter(now)) return null;
        var state = states.computeIfAbsent(id, key -> new State());
        if (state.until.isAfter(now) || state.probe) return null;
        boolean probe = !state.until.equals(Instant.MIN);
        if (probe) state.probe = true;
        return new Permit(id, state.epoch, probe);
    }

    /**
     * 旧并发请求不能关闭较新的OPEN状态；半开成功才解除当前冷却。
     */
    public synchronized void success(Permit permit) {
        var state = states.get(permit.id());
        if (state.epoch != permit.epoch()) return;
        state.failures = 0;
        state.until = Instant.MIN;
        state.probe = false;
    }

    /**
     * 只有服务故障累计开路；429立即冷却完整配额组并保留Retry-After。
     */
    public synchronized void failure(Permit permit, String quota, boolean rateLimited, Duration retryAfter) {
        var state = states.get(permit.id());
        if (state.epoch != permit.epoch()) return;
        state.probe = false;
        if (rateLimited) {
            var until = clock.instant().plus(retryAfter == null ? Duration.ofSeconds(policy.cooldownSeconds()) : retryAfter);
            quotas.merge(quota, until, (old, next) -> old.isAfter(next) ? old : next);
            state.until = until;
            state.epoch++;
        } else if (permit.probe() || ++state.failures >= policy.failureThreshold()) {
            state.until = clock.instant().plusSeconds(policy.cooldownSeconds());
            state.epoch++;
        }
    }

    /**
     * 本地预算／来源／并发失败未发请求，释放试探但不虚构服务失败。
     */
    public synchronized void release(Permit permit) {
        var state = states.get(permit.id());
        if (state.epoch == permit.epoch() && permit.probe()) state.probe = false;
    }

    /**
     * 认证或模型名称配置错误需重启修正，不在后续请求重复发送无效配置。
     */
    public synchronized void configurationError(Permit permit) {
        var state = states.get(permit.id());
        if (state.epoch == permit.epoch()) {
            state.until = Instant.MAX;
            state.probe = false;
            state.epoch++;
        }
    }
}
