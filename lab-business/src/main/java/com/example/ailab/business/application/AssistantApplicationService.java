package com.example.ailab.business.application;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.context.RequestCancellation;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.AiGatewayPort;
import com.example.ailab.contract.port.SessionStorePort;
import com.example.ailab.contract.port.KnowledgeCapabilityPort;
import org.springframework.stereotype.Service;

/**
 * 通过公共端口调用 AI，不依赖 SDK。
 */
@Service
public class AssistantApplicationService {
    private final AiGatewayPort ai;
    private final SessionStorePort sessions;
    private final KnowledgeCapabilityPort knowledge;

    /**
     * 注入运行时网关。
     */
    public AssistantApplicationService(AiGatewayPort ai, SessionStorePort sessions, KnowledgeCapabilityPort knowledge) {
        this.ai = ai;
        this.sessions = sessions;
        this.knowledge = knowledge;
    }

    /**
     * 身份独立于请求文本；最终交付复核由响应发送端调用。
     */
    public AiResult answer(UserContext actor, AiRequest request) {
        return ai.answer(actor, request);
    }

    /**
     * 定义查询也复核当前身份，不接受客户端指定执行者。
     */
    public java.util.List<ToolDefinition> tools(UserContext actor, String taskType) {
        knowledge.authorize(actor, ScopeRequest.self());
        return ai.tools(actor, taskType);
    }

    /**
     * 异步生成检查取消；发送端取得交付锁后再执行最终复核。
     */
    public AiResult answer(UserContext actor, AiRequest request, RequestCancellation cancellation) {
        var result = ai.answer(actor, request, cancellation);
        cancellation.check();
        return result;
    }

    /**
     * SSE 响应开始前同步核验参数、本人归属与版本，使这些失败保留真实 HTTP 状态。
     */
    public void validateRequest(UserContext actor, AiRequest request) {
        if (request == null || !request.validQuestion() || !request.validSession())
            throw com.example.ailab.contract.error.LabException.invalid("问题或会话参数不合法");
        var scope = request.scope();
        if (request.sessionId() != null) {
            var session = sessions.read(actor, request.sessionId());
            if (session.version() != request.sessionVersion())
                throw new com.example.ailab.contract.error.LabException("SESSION_CONFLICT", "会话版本已变化");
            if (scope == null) scope = session.scope();
        }
        knowledge.authorize(actor, scope);
    }

    /**
     * JSON 返回或 SSE 发送前重核完整历史来源；事务完成不代表浏览器已收到答案。
     */
    public void verifyDelivery(UserContext actor, AiRequest request, AiResult result) {
        if (result.sessionId() != null) {
            sessions.verifyDelivery(actor, result.sessionId(), result.sessionVersion());
        } else {
            knowledge.authorize(actor, request.scope());
            for (var citation : result.citations()) {
                var document = knowledge.documentMetadata(actor, request.scope(), citation.document().id());
                if (document.documentVersion() != citation.document().documentVersion())
                    throw com.example.ailab.contract.error.LabException.denied();
            }
        }
    }
}
