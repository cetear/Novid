package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 学习与媒体任务用例，任务与产物均隔离到本人。
 */
@Service
public class TaskApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final KnowledgeCapabilityPort knowledge;
    private final TaskStorePort tasks;
    private final ArtifactStorePort artifacts;
    private MediaApplicationService media;

    /**
     * 正式媒体用例显式装配；旧测试构造没有媒体能力，不伪造已实现。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void media(MediaApplicationService media) {
        this.media = media;
    }

    /**
     * 用例不依赖 AI Worker 或数据实现。
     */
    public TaskApplicationService(KnowledgeAccessPolicy p, KnowledgeCapabilityPort k, TaskStorePort t, ArtifactStorePort a) {
        policy = p;
        knowledge = k;
        tasks = t;
        artifacts = a;
    }

    /**
     * 只创建登记中的学习或媒体任务，已移除的工作流不映射为新功能。
     */
    public TaskSnapshot create(UserContext actor, TaskRequest r) {
        if (TaskRequest.retired(r.taskType())) throw new LabException("WORKFLOW_RETIRED", "旧FAQ与研究报告已移除，请使用学习自测或资料整编");
        boolean mediaType = Set.of("NOTES_PPT", "NOTES_VIDEO").contains(r.taskType());
        if (!TaskRequest.supported(r.taskType()) || r.topic() == null || r.topic().isBlank() || r.topic().length() > 1000 || r.documentIds().isEmpty() || r.documentIds().size() > 6 || r.documentIds().stream().anyMatch(id -> id == null || id <= 0))
            throw LabException.invalid("支持PPT、视频、自测和资料整编，指定1～6份资料和有限主题");
        // 学习任务固定执行；媒体兼容原strategy参数，实际架构由服务端选择。
        if (!Set.of("FIXED", "PLANNED").contains(r.strategy()) || r.strategy().equals("PLANNED") && !mediaType)
            throw LabException.invalid("学习任务strategy须为FIXED，媒体保留FIXED或PLANNED");
        if (Learning.supports(r.taskType())) {
            if (!r.strategy().equals("FIXED")) throw LabException.invalid("学习工作流由服务端固定分配FIXED架构");
            if (r.taskType().equals("QUIZ_GENERATION")) r.quizOptions().validate();
            else r.compilationOptions().validate();
        }
        if (r.quizOptions() != null && !r.taskType().equals("QUIZ_GENERATION")
                || r.compilationOptions() != null && !r.taskType().equals("KNOWLEDGE_COMPILATION"))
            throw LabException.invalid("工作流选项与任务类型不匹配");
        DocumentApplicationService.validateKey(r.idempotencyKey());
        policy.authorize(actor, r.scope());
        for (long id : r.documentIds()) knowledge.document(actor, r.scope(), id);
        if (mediaType) {
            if (media == null) throw new LabException("MEDIA_CAPABILITY_UNAVAILABLE", "媒体未装配");
            media.validate(actor, r);
        } else if (r.presentationOptions() != null || r.videoOptions() != null)
            throw LabException.invalid("学习任务不能携带媒体选项");
        return tasks.create(actor, r);
    }

    /**
     * 任何角色都仅读本人任务。
     */
    public TaskSnapshot read(UserContext actor, long id) {
        return tasks.read(policy.current(actor), id);
    }

    /**
     * 查询本人已校验计划，未产生时为空；ADMIN没有私人旁路。
     */
    public java.util.Optional<TaskPlanSnapshot> plan(UserContext actor, long id) {
        actor = policy.current(actor);
        tasks.read(actor, id);
        return tasks.readPlan(actor, id);
    }

    /**
     * 状态机由数据短事务执行，resume 不能作为用户确认。
     */
    public TaskSnapshot action(UserContext actor, long id, String action) {
        return tasks.action(policy.current(actor), id, action);
    }

    private com.example.ailab.contract.port.FixedWorkflowStorePort fixed;
    @org.springframework.beans.factory.annotation.Autowired
    public void fixedWorkflows(com.example.ailab.contract.port.FixedWorkflowStorePort fixed) { this.fixed = fixed; }
    public String result(UserContext actor, long id) { return fixed.result(policy.current(actor), id); }

    /**
     * 本人产物同时复核来源。
     */
    public ArtifactSnapshot artifact(UserContext actor, long id) {
        return artifacts.artifact(policy.current(actor), id);
    }

    /**
     * 二进制读取仍由存储验证本人、来源和checksum，不提供静态文件路径。
     */
    public byte[] artifactBytes(UserContext actor, long id) {
        return artifacts.bytes(policy.current(actor), id);
    }
}
