package com.example.ailab.data.search;

import com.github.benmanes.caffeine.cache.*;
import com.example.ailab.contract.dto.*;
import java.time.Duration;
import java.util.List;

/** 只缓存有界候选ID与明确版本，不缓存原文、模型答案、会话或偏好。 */
public final class QueryCandidateCache {
    /** 全局纪元同时覆盖内容和激活代次；所有Scope保守失效。策略仅为无提示词检索版本。 */
    public record Key(long actorUserId, String role, long permissionVersion, String scope,
                      long contentProcessingEpoch, String queryHash, String vectorHash,
                      String parserSplitRetrievalPolicy, String promptPolicy, String modelPolicy) { }
    private final Cache<Key, List<ChunkCandidate>> cache;

    /** 默认60秒、512键；TTL只用于内存治理，不能作为权限撤销机制。 */
    public QueryCandidateCache() { this(Ticker.systemTicker()); }

    /** 可控时钟仅用于缓存期限测试。 */
    public QueryCandidateCache(Ticker ticker) {
        cache = Caffeine.newBuilder().maximumSize(512).expireAfterWrite(Duration.ofSeconds(60))
                .ticker(ticker).recordStats().build();
    }

    /** 命中仅返回候选，实际included原文仍由MySQL重新授权及验证。 */
    public List<ChunkCandidate> get(Key key) { return cache.getIfPresent(key); }

    /** 不保留过多候选或可变调用者集合。 */
    public void put(Key key, List<ChunkCandidate> values) {
        if (values.size() > 40) throw new IllegalArgumentException("缓存候选超过40项");
        cache.put(key, List.copyOf(values));
    }

    /** 运维读取仅暴露单实例命中／未命中与近似键数，无名称标签。 */
    public long[] statistics() { return new long[]{cache.stats().hitCount(), cache.stats().missCount(), cache.estimatedSize()}; }
}
