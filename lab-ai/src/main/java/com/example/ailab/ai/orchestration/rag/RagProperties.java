package com.example.ailab.ai.orchestration.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import com.example.ailab.contract.dto.ContextPolicy;

/**
 * 8.3 参数集中定义；Token 计数当前用 UTF-8 字节保守上界，标记 ESTIMATED。
 */
@ConfigurationProperties("lab.rag")
public record RagProperties(int maxChunkTokens, int overlapTokens, int minChunkTokens, int headingPrefixMaxTokens,
                            int parentMaxTokens, int maxEvidenceTokens, int candidateChunks, int finalEvidence,
                            int neighborWindow, @DefaultValue("20") int retrievalPerRoute,
                            @DefaultValue("true") boolean preserveCodeBlocks,
                            @DefaultValue("true") boolean preserveTableRows,
                            @DefaultValue("true") boolean includeHeadingPath) {
    /**
     * 保留此前测试及 CLI 的构造入口，正式参数使用同一配置绑定。
     */
    public RagProperties(int maxChunkTokens, int overlapTokens, int minChunkTokens, int headingPrefixMaxTokens,
                         int parentMaxTokens, int maxEvidenceTokens, int candidateChunks, int finalEvidence, int neighborWindow) {
        this(maxChunkTokens, overlapTokens, minChunkTokens, headingPrefixMaxTokens, parentMaxTokens,
                maxEvidenceTokens, candidateChunks, finalEvidence, neighborWindow, 20, true, true, true);
    }

    /**
     * 校验所有有限上限与相互关系。
     */
    @ConstructorBinding
    public RagProperties {
        if (maxChunkTokens < 100 || maxChunkTokens > 500 || overlapTokens < 0 || overlapTokens >= maxChunkTokens || minChunkTokens < 1 || minChunkTokens > maxChunkTokens || headingPrefixMaxTokens < 1 || headingPrefixMaxTokens >= maxChunkTokens || parentMaxTokens < maxChunkTokens || parentMaxTokens > 2000 || maxEvidenceTokens < 500 || maxEvidenceTokens > 4000 || candidateChunks < 1 || candidateChunks > 6 || finalEvidence < 1 || finalEvidence > 6 || neighborWindow < 0 || neighborWindow > 1 || retrievalPerRoute < 1 || retrievalPerRoute > 20)
            throw new IllegalArgumentException("RAG 参数超出学习基线限制");
    }

    /**
     * 只输出扩展和检索必要参数，不把 AI 配置类传播到数据模块。
     */
    public ContextPolicy contextPolicy() {
        return new ContextPolicy(retrievalPerRoute, candidateChunks, finalEvidence, parentMaxTokens, neighborWindow, maxEvidenceTokens);
    }
}
