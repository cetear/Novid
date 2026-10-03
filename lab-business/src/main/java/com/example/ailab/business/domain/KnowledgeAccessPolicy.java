package com.example.ailab.business.domain;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 统一权限策略：管理员扩大资料读取范围，写入仍仅本人。
 */
@Component
public class KnowledgeAccessPolicy {
    private final UserStorePort users;
    private final KnowledgeBaseRepository bases;

    /**
     * 装配当前账户与知识库端口。
     */
    public KnowledgeAccessPolicy(UserStorePort users, KnowledgeBaseRepository bases) {
        this.users = users;
        this.bases = bases;
    }

    /**
     * 每次复核数据库身份，历史范围不能永久授权。
     */
    public UserContext current(UserContext actor) {
        if (actor == null) throw new LabException("AUTH_REQUIRED", "请先登录");
        var u = users.user(actor.userId()).orElseThrow(LabException::denied);
        if (!u.enabled() || u.role() != actor.role() || u.permissionVersion() != actor.permissionVersion())
            throw LabException.denied();
        if (u.passwordChangeRequired()) throw new LabException("PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
        return actor;
    }

    /**
     * 混合越权 ID 整体拒绝；空 SELECTED 保持零范围。
     */
    public AuthorizedKnowledgeScope authorize(UserContext actor, ScopeRequest request) {
        current(actor);
        request = request == null ? ScopeRequest.self() : request;
        if (request.knowledgeBaseIds().size() > 100 || request.knowledgeBaseIds().stream().anyMatch(id -> id == null || id <= 0))
            throw LabException.invalid("最多选择 100 个有效库 ID");
        if (request.mode() != ScopeRequest.Mode.SELECTED && !request.knowledgeBaseIds().isEmpty())
            throw LabException.invalid("仅 SELECTED 接受 ID 列表");
        if (actor.role() != UserContext.Role.ADMIN && (request.mode() == ScopeRequest.Mode.ALL || request.ownerUserId() != null && request.ownerUserId() != actor.userId()))
            throw LabException.denied();
        if (request.mode() == ScopeRequest.Mode.SELF && request.ownerUserId() != null && request.ownerUserId() != actor.userId())
            throw LabException.denied();
        if (request.mode() == ScopeRequest.Mode.SELECTED)
            for (long id : request.knowledgeBaseIds()) readable(actor, id);
        Long owner = request.mode() == ScopeRequest.Mode.SELF ? Long.valueOf(actor.userId()) : request.ownerUserId();
        return new AuthorizedKnowledgeScope(actor, request.mode(), request.knowledgeBaseIds().stream().distinct().sorted().toList(), owner, Instant.now());
    }

    /**
     * 读取要求启用且未删除；普通用户仅本人。
     */
    public KnowledgeBaseSnapshot readable(UserContext actor, long id) {
        current(actor);
        var k = bases.find(id).orElseThrow(LabException::denied);
        if (k.deleted() || !k.enabled() || actor.role() != UserContext.Role.ADMIN && k.ownerUserId() != actor.userId())
            throw LabException.denied();
        return k;
    }

    /**
     * owner 可恢复禁用库，但不能操作已删除库。
     */
    public KnowledgeBaseSnapshot writable(UserContext actor, long id) {
        current(actor);
        var k = bases.find(id).orElseThrow(LabException::denied);
        if (k.deleted() || k.ownerUserId() != actor.userId()) throw LabException.denied();
        return k;
    }
}
