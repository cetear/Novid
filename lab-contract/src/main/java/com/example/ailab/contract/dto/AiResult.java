package com.example.ailab.contract.dto;

import java.util.List;

/**
 * 已汇聚 AI 结果，引用区分 matched 与 included 范围。
 */
public record AiResult(String status, String answer, List<EvidenceBundle> citations, String traceId, String modelId,
                       int modelAttempts, boolean mock, String error) {
    /**
     * 防止交付后修改引用集合。
     */
    public AiResult {
        citations = List.copyOf(citations);
    }
}
