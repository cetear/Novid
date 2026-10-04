package com.example.ailab.contract.dto;

import java.util.List;

/**
 * 已汇聚 AI 结果，引用区分 matched 与 included 范围。
 */
public record AiResult(String status, String answer, List<EvidenceBundle> citations, String traceId, String modelId,
                       int modelAttempts, boolean mock, String error, Long sessionId, Long sessionVersion, ModelRoute route) {
    /** S01十参数结果兼容，不补假路由。 */
    public AiResult(String status, String answer, List<EvidenceBundle> citations, String traceId, String modelId,
                    int modelAttempts, boolean mock, String error, Long sessionId, Long sessionVersion) {
        this(status, answer, citations, traceId, modelId, modelAttempts, mock, error, sessionId, sessionVersion, null);
    }
    /** 旧单轮和后台构造不携带会话，不改变原有状态和引用含义。 */
    public AiResult(String status, String answer, List<EvidenceBundle> citations, String traceId, String modelId,
                    int modelAttempts, boolean mock, String error) {
        this(status, answer, citations, traceId, modelId, modelAttempts, mock, error, null, null);
    }
    /**
     * 防止交付后修改引用集合。
     */
    public AiResult {
        citations = List.copyOf(citations);
    }
}
