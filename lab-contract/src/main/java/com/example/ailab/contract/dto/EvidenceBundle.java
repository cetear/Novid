package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 EvidenceBundle，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record EvidenceBundle(String evidenceId, DocumentSnapshot document, long processingRevision, String sectionId, String headingPath, List<String> matchedChunkIds, List<String> includedChunkIds, int startOffset, int endOffset, String text) {
    /** 防御性复制列表，不能跨阶段修改证据。 */
    public EvidenceBundle { matchedChunkIds=List.copyOf(matchedChunkIds); includedChunkIds=List.copyOf(includedChunkIds); }
}
