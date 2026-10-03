package com.example.ailab.contract.dto;

/** 同版本、同代次的章节原文页；nextOffset 为绝对 UTF-16 游标，范围包括子章节。 */
public record SectionPage(long documentId, int documentVersion, long processingRevision,
                          String sectionId, String headingPath, int sectionStartOffset, int sectionEndOffset,
                          int startOffset, int endOffset, String text, Integer nextOffset, boolean complete,
                          int remainingStartOffset, int remainingEndOffset, int tokenCount, String countSource) { }
