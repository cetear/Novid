package com.example.ailab.contract.dto;

import java.util.List;

/** 经模型生成的有界摘要，覆盖序号与来源共同决定能否复用。 */
public record SessionSummary(String content, long coveredThroughSeq, List<SourceDependency> sourceDependencies) {
    /** 保留不可变的派生来源，不能仅凭摘要文本跳过授权。 */
    public SessionSummary {
        sourceDependencies = sourceDependencies == null ? List.of() : List.copyOf(sourceDependencies);
    }
}
