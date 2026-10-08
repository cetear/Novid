package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.KnowledgeCapabilityPort;
import com.example.ailab.contract.port.SessionStorePort;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本人会话用例；管理员资料读取能力不授予私人历史访问权。
 */
@Service
public class SessionApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final KnowledgeCapabilityPort knowledge;
    private final SessionStorePort sessions;

    /**
     * 会话持久化与知识授权使用独立窄端口，业务层不引用 SQL 或模型 SDK。
     */
    public SessionApplicationService(KnowledgeAccessPolicy policy, KnowledgeCapabilityPort knowledge,
                                     SessionStorePort sessions) {
        this.policy = policy;
        this.knowledge = knowledge;
        this.sessions = sessions;
    }

    /**
     * 标题和幂等键先验证；所有者只取当前服务端身份。
     */
    public SessionSnapshot create(UserContext actor, String title, String idempotencyKey) {
        if (title == null || title.isBlank() || title.length() > 200)
            throw LabException.invalid("会话标题须为 1～200 字符");
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9_.:-]{1,128}"))
            throw LabException.invalid("幂等键须为 1～128 个字母、数字或 _ . : -");
        return sessions.create(policy.current(actor), title, idempotencyKey);
    }

    /**
     * 分页只读取本人会话，限制偏移运算避免溢出成错误范围。
     */
    public List<SessionSnapshot> list(UserContext actor, int page, int size) {
        if (page < 0 || page > 10000 || size < 1 || size > 100)
            throw LabException.invalid("分页超出限制");
        return sessions.list(policy.current(actor), page * size, size);
    }

    /**
     * 他人和不存在的 ID 统一由存储端口返回 ACCESS_DENIED。
     */
    public SessionSnapshot read(UserContext actor, long id) {
        positive(id, "会话 ID");
        return sessions.read(policy.current(actor), id);
    }

    /**
     * 完整历史与模型窗口分离；受限事件保留序号但不输出正文或来源。
     */
    public List<SessionMessage> messages(UserContext actor, long id, long afterSeq, int size) {
        positive(id, "会话 ID");
        if (afterSeq < 0 || size < 1 || size > 100)
            throw LabException.invalid("消息分页超出限制");
        policy.current(actor);
        var session = sessions.read(actor, id);
        var result = sessions.messages(actor, id, afterSeq, size).stream()
                .map(message -> readableMessage(actor, session.scope(), message)).toList();
        // 分页期间范围切换或删除不能使用过时元数据完成发布；调用方刷新版本后重读。
        policy.current(actor);
        var latest = sessions.read(actor, id);
        if (latest.version() != session.version() || !latest.scope().equals(session.scope()))
            throw new LabException("SESSION_CONFLICT", "会话在读取期间已变化");
        // 逐条核验后统一短事务复核本页可见来源，防止前一条检查通过后被撤销仍继续发布。
        sessions.verifyHistoryDelivery(actor, id, session.version(), result);
        policy.current(actor);
        return result;
    }

    /**
     * 删除使用本人身份与明确版本；远程模型执行不能绕过版本化软删。
     */
    public void delete(UserContext actor, long id, long version) {
        positive(id, "会话 ID");
        positive(version, "会话版本");
        sessions.delete(policy.current(actor), id, version);
    }

    /**
     * 每条历史重授权原始意图和当前范围，角色变更不能继承旧 ADMIN 授权。
     */
    private SessionMessage readableMessage(UserContext actor, ScopeRequest currentScope, SessionMessage message) {
        try {
            knowledge.authorize(actor, message.scope());
            knowledge.authorize(actor, currentScope);
            if (message.sourceDependencies().size() > 32 || message.sourceReferences().size() > 32)
                throw new LabException("CONTEXT_MAPPING_INVALID", "历史来源数量超限");
            // 引用不能携带未列入不可变来源的额外文档。
            if (message.sourceReferences().stream().anyMatch(ref -> ref.dependency() == null
                    || !message.sourceDependencies().contains(ref.dependency())))
                throw new LabException("CONTEXT_MAPPING_INVALID", "历史引用与来源不匹配");
            verifySources(actor, currentScope, message.sourceDependencies(), new HashSet<>(), new HashSet<>(), 0);
            return message;
        } catch (LabException failure) {
            // 基础设施或模型错误不能伪装成权限过滤；只隐藏已失效的授权/映射事实。
            if (!Set.of("ACCESS_DENIED", "CONTEXT_MAPPING_INVALID").contains(failure.code())) throw failure;
            return new SessionMessage(message.seq(), message.role(), "RESTRICTED", null, List.of(), List.of(),
                    message.toolCallId(), message.toolName(), message.createdAt(), ScopeRequest.self());
        }
    }

    /**
     * 派生资料全部来源也须在当前范围内，且当前版本与不可变历史版本完全吻合。
     */
    private void verifySources(UserContext actor, ScopeRequest scope, List<SourceDependency> sources,
                               Set<SourceDependency> path, Set<SourceDependency> visited, int depth) {
        if (depth > 8 || sources.size() > 32)
            throw new LabException("CONTEXT_MAPPING_INVALID", "历史来源层级超限");
        for (var source : sources) {
            if (path.contains(source) || source.documentId() <= 0 || source.knowledgeBaseId() <= 0
                    || source.documentVersion() <= 0)
                throw new LabException("CONTEXT_MAPPING_INVALID", "历史来源存在环或无效标识");
            if (visited.contains(source)) continue;
            if (visited.size() + path.size() >= 32)
                throw new LabException("CONTEXT_MAPPING_INVALID", "历史来源总量超限");
            var content = knowledge.document(actor, scope, source.documentId());
            var document = content.document();
            if (document.id() != source.documentId() || document.knowledgeBaseId() != source.knowledgeBaseId()
                    || document.documentVersion() != source.documentVersion()) throw LabException.denied();
            path.add(source);
            verifySources(actor, scope, content.sourceDependencies(), path, visited, depth + 1);
            path.remove(source);
            visited.add(source);
        }
    }

    /**
     * 非正 ID 或版本在进入存储前拒绝，避免错误值被当作不存在资源。
     */
    private static void positive(long value, String name) {
        if (value <= 0) throw LabException.invalid(name + "须为正数");
    }
}
