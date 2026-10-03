package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.KnowledgeBaseRepository;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 知识库 CRUD 不必调用 AI。
 */
@Service
public class KnowledgeBaseApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final KnowledgeBaseRepository bases;

    /**
     * 注入策略与存储端口。
     */
    public KnowledgeBaseApplicationService(KnowledgeAccessPolicy policy, KnowledgeBaseRepository bases) {
        this.policy = policy;
        this.bases = bases;
    }

    /**
     * owner 固定为可信请求者。
     */
    public KnowledgeBaseSnapshot create(UserContext actor, String name, String description) {
        validate(name, description);
        return bases.create(policy.current(actor), name, description);
    }

    /**
     * 受控分页和统一范围过滤。
     */
    public List<KnowledgeBaseSnapshot> list(UserContext actor, ScopeRequest scope, int page, int size) {
        AccountApplicationService.page(page, size);
        return bases.list(policy.authorize(actor, scope), page * size, size);
    }

    /**
     * owner 可查看禁用库元数据，资料读取仍要求启用。
     */
    public KnowledgeBaseSnapshot read(UserContext actor, long id) {
        policy.current(actor);
        var k = bases.find(id).orElseThrow(LabException::denied);
        return k.ownerUserId() == actor.userId() && !k.deleted() ? k : policy.readable(actor, id);
    }

    /**
     * 自有库按版本 CAS 修改。
     */
    public KnowledgeBaseSnapshot update(UserContext actor, long id, long version, String name, String description, boolean enabled) {
        validate(name, description);
        policy.writable(actor, id);
        return bases.update(actor, id, version, name, description, enabled);
    }

    /**
     * 手工删除只要求明确命令与 owner 权限。
     */
    public void delete(UserContext actor, long id, long version) {
        policy.writable(actor, id);
        bases.delete(actor, id, version);
    }

    /**
     * 控制名称和描述长度。
     */
    private void validate(String name, String description) {
        if (name == null || name.isBlank() || name.length() > 200 || description == null || description.length() > 2000)
            throw LabException.invalid("库名称或描述超出限制");
    }
}
