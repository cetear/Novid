package com.example.ailab.contract.dto;

/** 不可变引用位置；处理代次和 UTF-16 偏移允许为空，供后续来源映射补齐。 */
public record SessionSource(SourceDependency dependency, Long processingRevision, String sectionId,
                            Integer startOffset, Integer endOffset) {
}
