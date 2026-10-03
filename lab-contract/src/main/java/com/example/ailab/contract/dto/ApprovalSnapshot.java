package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * ApprovalSnapshot 的跨模块不可变快照，不包含 ORM 对象。
 */
public record ApprovalSnapshot(String approvalId, String operationId, long actorUserId, long knowledgeBaseId,
                               long targetVersion, String title, String content,
                               List<SourceDependency> sourceDependencies, Instant expiresAt, String status,
                               Long documentId) {
    /**
     * 防御性复制集合，防止跨模块修改结果。
     */
    public ApprovalSnapshot {
        sourceDependencies = List.copyOf(sourceDependencies);
    }

}

