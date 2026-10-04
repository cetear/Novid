package com.example.ailab.business.application;

import com.github.benmanes.caffeine.cache.*;
import com.example.ailab.contract.error.LabException;
import java.time.Duration;

/** 固定五分钟窗口，容量满时拒绝新名称，绝不淘汰仍有效的限流事实。 */
public final class LoginRateLimiter {
    private final Cache<String, int[]> attempts;
    private final int capacity;

    /** 正式默认只保存最多一万个哈希名称；窗口内增量不重写到期时间。 */
    public LoginRateLimiter() { this(10000, Ticker.systemTicker()); }

    /** 可控时钟仅用于程序验收，避免真实等待五分钟。 */
    public LoginRateLimiter(int capacity, Ticker ticker) {
        if (capacity < 1 || capacity > 10000) throw new IllegalArgumentException("限流容量超限");
        this.capacity = capacity;
        attempts = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).ticker(ticker).build();
    }

    /** 同步仅覆盖有界计数；密码哈希和数据库访问不持此锁，不逐请求扫描所有名称。 */
    public synchronized void acquire(String nameHash) {
        var bucket = attempts.getIfPresent(nameHash);
        if (bucket == null) {
            if (attempts.estimatedSize() >= capacity) attempts.cleanUp();
            if (attempts.estimatedSize() >= capacity) throw limited();
            bucket = new int[1];
            attempts.put(nameHash, bucket);
        }
        if (bucket[0] >= 5) throw limited();
        bucket[0]++;
    }

    /** 稳定错误不会透露用户是否存在。 */
    private LabException limited() { return new LabException("RATE_LIMITED", "登录繁忙或超过五分钟窗口限额"); }
}
