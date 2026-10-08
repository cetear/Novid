package com.example.ailab.contract.dto;

import java.util.List;

/**
 * 单次脱敏路由与用量快照，不含地址、凭证或提示词，不替代后续持久追踪。
 */
public record ModelRoute(String policyVersion, String qualityVersion, String profile, String routingMode,
                         String selectedModelId, String selectionReason, List<String> fallbackCandidates,
                         List<Attempt> attempts) {
    /**
     * 集合不可变；失败与修复用量可空，未知不视为免费。
     */
    public ModelRoute {
        fallbackCandidates = List.copyOf(fallbackCandidates);
        attempts = List.copyOf(attempts);
    }

    /**
     * 保守输入与提供方词元分列，UNKNOWN价格引用不表示免费。
     */
    public record Attempt(String modelId, String outcome, int reservedInputTokens, String countSource,
                          Integer inputTokens, Integer outputTokens, String usageSource, String priceRef) {
    }
}
