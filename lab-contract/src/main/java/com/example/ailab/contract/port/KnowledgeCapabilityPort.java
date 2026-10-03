package com.example.ailab.contract.port;
import java.util.*;
import java.time.Instant;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;
/** KnowledgeCapabilityPort 窄端口，实现由运行入口装配。 */
public interface KnowledgeCapabilityPort {
    /** 生成并复核可信范围。 */ AuthorizedKnowledgeScope authorize(UserContext actor, ScopeRequest request);
    /** 按当前范围读取真实资料。 */ DocumentContent document(UserContext actor, ScopeRequest request, long documentId);
    /** 读取真实 SQL 统计。 */ KnowledgeStatistics statistics(UserContext actor, ScopeRequest request);
}

