package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * DocumentStorePort 窄端口，实现由运行入口装配。
 */
public interface DocumentStorePort {
    /**
     * 原子登记原文版本、请求去重和入库 Outbox。
     */
    DocumentSnapshot create(UserContext actor, UploadCommand command);

    /**
     * 在授权范围内分页列出文档元数据。
     */
    List<DocumentSnapshot> list(AuthorizedKnowledgeScope scope, int offset, int limit);

    /**
     * 当前原文读取与衍生来源递归复核。
     */
    DocumentContent read(AuthorizedKnowledgeScope scope, long documentId);

    /**
     * 自有文档 CAS 修订，保留服务器来源约束。
     */
    DocumentSnapshot revise(UserContext actor, long documentId, int expectedVersion, String title, String text);

    /**
     * 自有文档删除及 Outbox 同事务提交。
     */
    void delete(UserContext actor, long documentId, int expectedVersion);

    /**
     * 由 SQL 计算授权范围统计。
     */
    KnowledgeStatistics statistics(AuthorizedKnowledgeScope scope);

    /**
     * 复核所有历史来源版本仍保留且当前可读。
     */
    void verifySources(AuthorizedKnowledgeScope scope, List<SourceDependency> sources);
}

