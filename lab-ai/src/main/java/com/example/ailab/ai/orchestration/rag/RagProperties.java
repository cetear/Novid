package com.example.ailab.ai.orchestration.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 8.3 参数集中定义；Token 计数当前用 UTF-8 字节保守上界，标记 ESTIMATED。
 */
@ConfigurationProperties("lab.rag")
public record RagProperties(int maxChunkTokens, int overlapTokens, int minChunkTokens, int headingPrefixMaxTokens,
                            int parentMaxTokens, int maxEvidenceTokens, int candidateChunks, int finalEvidence,
                            int neighborWindow) {
    /**
     * 校验所有有限上限与相互关系。
     */
    public RagProperties {
        if (maxChunkTokens < 100 || maxChunkTokens > 500 || overlapTokens < 0 || overlapTokens >= maxChunkTokens || headingPrefixMaxTokens < 1 || headingPrefixMaxTokens >= maxChunkTokens || parentMaxTokens < maxChunkTokens || parentMaxTokens > 2000 || maxEvidenceTokens < 500 || maxEvidenceTokens > 4000 || candidateChunks < 1 || candidateChunks > 6 || finalEvidence < 1 || finalEvidence > 6 || neighborWindow != 1)
            throw new IllegalArgumentException("RAG 参数超出学习基线限制");
    }
}
