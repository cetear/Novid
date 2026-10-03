package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 ChunkSnapshot，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record ChunkSnapshot(String chunkId, String sectionId, String contextParentId, int chunkIndexInSection, int chunkIndexInParent, int startOffset, int endOffset, String rawText, String embeddingText, String chunkHash) {
}
