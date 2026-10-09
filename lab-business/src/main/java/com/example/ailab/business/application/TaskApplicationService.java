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
        if (!TaskRequest.supported(r.taskType()) || !r.documentDriven() && (r.topic() == null || r.topic().isBlank())
                || r.topic() != null && r.topic().length() > 1000 || r.documentIds().isEmpty() || r.documentIds().size() > 6 || r.documentIds().stream().anyMatch(id -> id == null || id <= 0))
            throw LabException.invalid("支持PPT、视频、自测和资料整编，指定1～6份资料和有限主题");
        if (r.documentDriven() && r.topic() != null || !r.documentDriven() && r.remarks() != null
                || r.remarks() != null && r.remarks().length() > 1000)
            throw LabException.invalid("资料工作流只接受可选remarks；视频使用必填topic");
        if (!r.strategy().equals(r.documentDriven() ? "FIXED" : "PLANNED"))
            throw LabException.invalid("资料工作流由服务端固定执行；视频使用PLANNED");
        if (Learning.supports(r.taskType())) {
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
     * 状态机由数据短事务执行，resume 不能作为用户确认。
     */
    public TaskSnapshot action(UserContext actor, long id, String action) {
        return tasks.action(policy.current(actor), id, action);
    }

    private ContentWorkflowStorePort content;
    @org.springframework.beans.factory.annotation.Autowired
    public void contentWorkflows(ContentWorkflowStorePort content) { this.content=content; }
    public String result(UserContext actor, long id) {
        return content.result(policy.current(actor), id);
    }
    public Optional<ContentWorkflow.Snapshot> contentPlan(UserContext actor,long id) {
        actor=policy.current(actor);tasks.read(actor,id);
        return content.readPlan(actor,id);
    }

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
