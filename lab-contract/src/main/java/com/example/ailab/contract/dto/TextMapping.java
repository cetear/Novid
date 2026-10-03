package com.example.ailab.contract.dto;

/** 检索正文片段到原文的精确映射；UTF-16 起含终不含，行号从一开始。标题前缀不冒充正文。 */
public record TextMapping(int embeddingStartOffset, int embeddingEndOffset, int sourceStartOffset,
                          int sourceEndOffset, int startLine, int endLine, String blockType,
                          String blockId, int partIndex, boolean repeatedHeader) { }
