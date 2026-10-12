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

    /** 只核验历史来源存在和当前权限，不读取祖先正文，也不受本次正文scope限制。 */
    void verifySources(UserContext actor, List<SourceDependency> sources);

    /** 核验当前正文版本、解析代次和可信摘要，不重新传输正文。 */
    default void verifyReadingDocuments(UserContext actor, ScopeRequest request, List<ContentReading.Document> documents) {
        // 兼容现有原生验收适配器；生产适配器覆盖为元数据查询。
        for(var d:documents) {
            var current=document(actor,request,d.documentId());var snapshot=current.document();
            if(snapshot.knowledgeBaseId()!=d.knowledgeBaseId()||snapshot.documentVersion()!=d.documentVersion()
                    ||!Objects.equals(snapshot.activeProcessingRevision(),d.processingRevision())||!Learning.digest(current.text()).equals(d.textHash()))
                throw new com.example.ailab.contract.error.LabException("CONTEXT_VERSION_CONFLICT","创建时固定的正文版本或解析代次已变化");
        }
    }

    /**
     * 读取真实 SQL 统计。
     */
    KnowledgeStatistics statistics(UserContext actor, ScopeRequest request);
}

