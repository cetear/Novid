package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 IndexedChunk，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record IndexedChunk(long knowledgeBaseId, long ownerUserId, long documentId, int documentVersion, long processingRevision, ChunkSnapshot chunk, List<Float> vector, String embeddingModelVersion) {
    /** 防御性复制列表，不能跨阶段修改证据。 */
    public IndexedChunk { vector=List.copyOf(vector); }
}
