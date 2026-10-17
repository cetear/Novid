package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * KnowledgeCapabilityPort 窄端口，实现由运行入口装配。
 */
public interface KnowledgeCapabilityPort {
    /**
     * 生成并复核可信范围。
     */
    AuthorizedKnowledgeScope authorize(UserContext actor, ScopeRequest request);

    /**
     * 按当前范围读取真实资料。
     */
    DocumentContent document(UserContext actor, ScopeRequest request, long documentId);

    /** 每次核验当前文档及来源权限，仅返回元数据；生产适配器避免读取全文。 */
    DocumentSnapshot documentMetadata(UserContext actor, ScopeRequest request, long documentId);

    /** 只核验历史来源存在和当前权限，不读取祖先正文，也不受本次正文scope限制。 */
    void verifySources(UserContext actor, List<SourceDependency> sources);

    /** 核验当前正文版本、解析代次和可信摘要，不重新传输正文。 */
    void verifyReadingDocuments(UserContext actor, ScopeRequest request, List<ContentReading.Document> documents);

    /**
     * 读取真实 SQL 统计。
     */
    KnowledgeStatistics statistics(UserContext actor, ScopeRequest request);
}

