package com.example.ailab.contract.dto;

/**
 * AI 参数经纯 Java 窄契约传给数据端；数据模块不反向引用 AI 配置或 SDK。
 */
public record ContextPolicy(int retrievalPerRoute, int candidateChunks, int finalEvidence,
                            int parentMaxTokens, int neighborWindow, int maxEvidenceTokens) {
    /**
     * 服务端上限不可由客户端或模型放宽。
     */
    public ContextPolicy {
        if (retrievalPerRoute < 1 || retrievalPerRoute > 20 || candidateChunks < 1 || candidateChunks > 6
                || finalEvidence < 1 || finalEvidence > 6 || parentMaxTokens < 100 || parentMaxTokens > 2000
                || neighborWindow < 0 || neighborWindow > 1 || maxEvidenceTokens < 500 || maxEvidenceTokens > 4000)
            throw new IllegalArgumentException("上下文参数超限");
    }
}
