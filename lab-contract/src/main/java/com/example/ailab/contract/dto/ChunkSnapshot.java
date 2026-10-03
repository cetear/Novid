package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * 版本化 ChunkSnapshot，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。
 */
public record ChunkSnapshot(String chunkId, String sectionId, String contextParentId, int chunkIndexInSection,
                            int chunkIndexInParent, int startOffset, int endOffset, String rawText,
                            String embeddingText, String chunkHash, String blockType, String blockId,
                            int partIndex, List<TextMapping> sourceMap, int tokenCount, String countSource) {
    /** 兼容旧构造与存量批次；缺映射明确为 LEGACY，不能声称已完成块映射。 */
    public ChunkSnapshot(String chunkId, String sectionId, String contextParentId, int chunkIndexInSection,
                         int chunkIndexInParent, int startOffset, int endOffset, String rawText,
                         String embeddingText, String chunkHash) {
        this(chunkId, sectionId, contextParentId, chunkIndexInSection, chunkIndexInParent, startOffset,
                endOffset, rawText, embeddingText, chunkHash, "LEGACY", null, 0, List.of(),
                embeddingText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, "ESTIMATED_UTF8_BYTES");
    }
    /** 旧 JSON 没有 sourceMap 时兼容为空；新批次不可变映射不交给调用方修改。 */
    public ChunkSnapshot { sourceMap = sourceMap == null ? List.of() : List.copyOf(sourceMap); }
}
