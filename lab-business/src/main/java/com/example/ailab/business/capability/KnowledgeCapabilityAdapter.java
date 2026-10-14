package com.example.ailab.business.capability;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import org.springframework.stereotype.Component;

/**
 * 独立能力适配，不回调问答用例，避免运行时循环依赖。
 */
@Component
public class KnowledgeCapabilityAdapter implements KnowledgeCapabilityPort {
    private final KnowledgeAccessPolicy policy;
    private final DocumentStorePort documents;

    /**
     * 装配业务策略与原文端口。
     */
    public KnowledgeCapabilityAdapter(KnowledgeAccessPolicy policy, DocumentStorePort documents) {
        this.policy = policy;
        this.documents = documents;
    }

    /**
     * 生成可信范围。
     */
    public AuthorizedKnowledgeScope authorize(UserContext actor, ScopeRequest scope) {
        return policy.authorize(actor, scope);
    }

    /**
     * 范围只能缩小，不能被模型扩大。
     */
    public DocumentContent document(UserContext actor, ScopeRequest scope, long id) {
        return documents.read(authorize(actor, scope), id);
    }

    public DocumentSnapshot documentMetadata(UserContext actor, ScopeRequest scope, long id) {
        return documents.readMetadata(authorize(actor, scope), id);
    }

    public void verifySources(UserContext actor, java.util.List<SourceDependency> sources) {
        var scope = new ScopeRequest(actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF,
                java.util.List.of(), null);
        documents.verifySources(authorize(actor, scope), sources);
    }

    public void verifyReadingDocuments(UserContext actor, ScopeRequest scope, java.util.List<ContentReading.Document> frozen) {
        documents.verifyReadingDocuments(authorize(actor, scope), frozen);
    }

    /**
     * 数值来自 SQL，模型只解释。
     */
    public KnowledgeStatistics statistics(UserContext actor, ScopeRequest scope) {
        return documents.statistics(authorize(actor, scope));
    }
}
