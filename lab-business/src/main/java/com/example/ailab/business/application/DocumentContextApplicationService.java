package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentContextPort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 章节与处理元数据使用统一知识授权，私人会话权限不能替代文档权限。
 */
@Service
public class DocumentContextApplicationService {
    private final DocumentApplicationService documents;
    private final KnowledgeAccessPolicy policy;
    private final DocumentContextPort context;

    /**
     * 业务只接窄端口，不触碰 SQL 或解析 SDK。
     */
    public DocumentContextApplicationService(DocumentApplicationService documents, KnowledgeAccessPolicy policy, DocumentContextPort context) {
        this.documents = documents;
        this.policy = policy;
        this.context = context;
    }

    /**
     * 服务端先读取文档推导库，再按当前角色验证范围；数据端会在短事务再次复核。
     */
    private AuthorizedKnowledgeScope scope(UserContext actor, long id) {
        var document = documents.read(actor, id).document();
        return policy.authorize(actor, new ScopeRequest(ScopeRequest.Mode.SELECTED, List.of(document.knowledgeBaseId()), null));
    }

    /**
     * 显式版本／代次必须与当前原文一致，历史位置不落到新版正文。
     */
    public SectionPage section(UserContext actor, long id, String sectionId, int version, long revision, Integer after, int maxTokens) {
        return context.sectionPage(scope(actor, id), id, version, revision, sectionId, after, maxTokens);
    }

    /**
     * 失败意图也可查询，但授权失败不能获得处理计数或错误信息。
     */
    public IngestionMetadata ingestion(UserContext actor, long id) {
        return context.ingestion(scope(actor, id), id);
    }
}
