package com.example.ailab.contract.dto;
import com.example.ailab.contract.context.UserContext;
import java.time.Instant;
import java.util.List;
/** 服务端生成的范围快照；data 仍复核当前用户权限版本和库状态。 */
public record AuthorizedKnowledgeScope(UserContext actor, ScopeRequest.Mode mode, List<Long> knowledgeBaseIds, Long ownerUserId, Instant generatedAt) {
    /** 对范围集合做不可变复制。 */
    public AuthorizedKnowledgeScope { knowledgeBaseIds = List.copyOf(knowledgeBaseIds); }
}

