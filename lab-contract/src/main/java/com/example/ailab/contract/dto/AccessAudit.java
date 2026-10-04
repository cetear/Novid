package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

/** 访问元数据不含标题、问题、原文、偏好或凭证；历史未知字段保留空值。 */
public record AccessAudit(long id, long actorUserId, String action, Long resourceId,
                          String scopeMode, Long permissionVersion, Long knowledgeEpoch,
                          Integer resultCount, String outcome, Instant createdAt,
                          List<Long> knowledgeBaseIds, Long ownerUserId, List<Long> resourceIds) {
    /** 旧审计无范围及对象集合事实，保持未知而不是补造空范围。 */
    public AccessAudit(long id, long actorUserId, String action, Long resourceId, String scopeMode,
                       Long permissionVersion, Long knowledgeEpoch, Integer resultCount, String outcome, Instant createdAt) {
        this(id, actorUserId, action, resourceId, scopeMode, permissionVersion, knowledgeEpoch, resultCount, outcome, createdAt, null, null, null);
    }
    /** 元数据集合不可变；null区分旧未知与新空结果。 */
    public AccessAudit {
        knowledgeBaseIds = knowledgeBaseIds == null ? null : List.copyOf(knowledgeBaseIds);
        resourceIds = resourceIds == null ? null : List.copyOf(resourceIds);
    }
}
