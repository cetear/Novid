package com.example.ailab.contract.dto;

/**
 * 持久页次及已读前缀／剩余原文范围；读取完成仅表示已交给分页研究模型，不表示效果评分通过。
 */
public record DocumentCoverage(long documentId, int documentVersion, long processingRevision,
                               String sectionId, int completedPages, int readStartOffset, int readEndOffset,
                               int remainingStartOffset, int remainingEndOffset, boolean complete,
                               String countSource) {
}
