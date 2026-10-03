package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 私人偏好、确认与运行记录，没有 ADMIN 旁路。
 */
@Service
public class PersonalApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final MemoryStorePort memories;
    private final OperationStorePort operations;
    private final TraceRecordPort traces;

    /**
     * 私人资源各用独立端口。
     */
    public PersonalApplicationService(KnowledgeAccessPolicy p, MemoryStorePort m, OperationStorePort o, TraceRecordPort t) {
        policy = p;
        memories = m;
        operations = o;
        traces = t;
    }

    /**
     * 完整内容与真实来源绑定到待确认记录。
     */
    public ApprovalSnapshot prepare(UserContext actor, long base, String title, String content, List<SourceDependency> sources) {
        DocumentApplicationService.validateText(title, content);
        policy.writable(actor, base);
        if (sources == null || sources.isEmpty() || sources.size() > 32)
            throw LabException.invalid("需要 1～32 个真实来源");
        return operations.prepare(actor, base, title, content, sources);
    }

    /**
     * 只提交决定，参数从服务端记录读取。
     */
    public ApprovalSnapshot decide(UserContext actor, String id, boolean approve) {
        policy.current(actor);
        return operations.decide(actor, id, approve);
    }

    /**
     * 本人预览必须重检来源。
     */
    public ApprovalSnapshot approval(UserContext actor, String id) {
        policy.current(actor);
        return operations.read(actor, id);
    }

    /**
     * 本人偏好列表。
     */
    public List<MemorySnapshot> memories(UserContext actor) {
        return memories.list(policy.current(actor));
    }

    /**
     * 用户明确命令创建偏好。
     */
    public MemorySnapshot createMemory(UserContext actor, String content) {
        validate(content);
        return memories.create(policy.current(actor), content);
    }

    /**
     * 版本化更正。
     */
    public MemorySnapshot updateMemory(UserContext actor, long id, long version, String content) {
        validate(content);
        return memories.update(policy.current(actor), id, version, content);
    }

    /**
     * 版本化删除。
     */
    public void deleteMemory(UserContext actor, long id, long version) {
        memories.delete(policy.current(actor), id, version);
    }

    /**
     * 本人运行列表。
     */
    public List<TraceSnapshot> runs(UserContext actor, int page, int size) {
        AccountApplicationService.page(page, size);
        return traces.list(policy.current(actor), page * size, size);
    }

    /**
     * 本人运行详情。
     */
    public TraceSnapshot run(UserContext actor, String id) {
        return traces.read(policy.current(actor), id);
    }

    /**
     * 控制个人偏好输入。
     */
    private void validate(String content) {
        if (content == null || content.isBlank() || content.length() > 2000)
            throw LabException.invalid("偏好须为 1～2000 字符");
    }
}
