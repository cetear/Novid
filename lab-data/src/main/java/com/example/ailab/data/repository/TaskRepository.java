package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.TaskMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/**
 * 短事务任务状态机，外部模型与报告生成均在事务外。
 */
@Repository
public class TaskRepository implements TaskStorePort, ArtifactStorePort {
    private final TaskMapper mapper;
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean workerEnabled;
    @org.springframework.beans.factory.annotation.Value("${lab.media.worker-enabled:false}")
    private boolean mediaWorkerEnabled;

    /**
     * 使用同一权威数据库与来源规则。
     */
    public TaskRepository(SqlSupport sql, DocumentSqlRepository docs) {
        this(sql, docs, true);
    }

    /** 正式查询返回本实例 Worker 配置；独立维护测试可显式传入开关。 */
    @org.springframework.beans.factory.annotation.Autowired
    public TaskRepository(SqlSupport sql, DocumentSqlRepository docs,
                          @org.springframework.beans.factory.annotation.Value("${lab.task.worker-enabled:false}") boolean workerEnabled) {
        this.sql = sql; this.mapper = sql.mapper(TaskMapper.class);
        this.docs = docs;
        this.workerEnabled = workerEnabled;
    }

    /**
     * 创建／参数 hash／用户命名空间去重在一个事务内。
     */
    @Transactional
    public TaskSnapshot create(UserContext actor, TaskRequest request) {
        sql.actor(actor, true);
        // FIXED保留S04之前的规范JSON，旧幂等键不能因增加可选字段而参数冲突。
        var canonical = json.valueToTree(new TaskRequest(request.taskType(), request.topic(), request.scope(), request.documentIds().stream().distinct().sorted().toList(), request.idempotencyKey(), request.strategy(), request.presentationOptions(), request.videoOptions()));
        if (request.strategy().equals("FIXED")) ((com.fasterxml.jackson.databind.node.ObjectNode) canonical).remove("strategy");
        // 老FAQ／报告幂等JSON不因新增媒体可选字段变化。
        if (!media(request.taskType())) ((com.fasterxml.jackson.databind.node.ObjectNode)canonical).remove(java.util.List.of("presentationOptions","videoOptions"));
        String serialized = encode(canonical), hash = SqlSupport.hash(serialized);
        var old = sql.project(mapper.createRequestDeduplicationsSelect(new Object[]{actor.userId(), request.idempotencyKey()}), (r, n) -> Map.entry(r.string(1), r.longValue(2)));
        if (!old.isEmpty()) {
            if (!old.get(0).getKey().equals(hash)) throw new LabException("OPERATION_CONFLICT", "相同任务键参数不同");
            return read(actor, old.get(0).getValue());
        }
        if (sql.scalar(mapper.createAiTasksSelect(new Object[]{actor.userId()}), Long.class) >= 20)
            throw new LabException("RATE_LIMITED", "未完成任务数量超过限额");
        long id = sql.insert(command -> mapper.createAiTasksInsert(command), actor.userId(), request.taskType(), serialized, hash);
        // 与任务创建同事务初始化步骤，202 返回时就有完整的待执行列表。
        for (String step : TaskProgress.stepIds())
            mapper.createTaskStepProgressWrite(new Object[]{id, step});
        mapper.createRequestDeduplicationsWrite(new Object[]{actor.userId(), request.idempotencyKey(), hash, id});
        return read(actor, id);
    }

    /**
     * 任何角色都必须是请求者。
     */
    @Transactional(readOnly = true)
    public TaskSnapshot read(UserContext actor, long id) {
        sql.actor(actor, false);
        return sql.project(mapper.readAiTasksSelect(new Object[]{id, actor.userId()}), this::task).stream().findFirst().orElseThrow(LabException::denied);
    }

    /**
     * pause/cancel 立即 fencing，当前模型可继续计费但不能提交检查点／产物。
     */
    @Transactional
    public TaskSnapshot action(UserContext actor, long id, String action) {
        sql.actor(actor, true);
        var t = read(actor, id);
        String state = switch (action) {
            case "pause" -> {
                if (!Set.of("QUEUED", "RUNNING", "WAITING_EXTERNAL").contains(t.status()))
                    throw new LabException("OPERATION_CONFLICT", "当前状态不能暂停");
                yield "PAUSED";
            }
            case "resume" -> {
                if (!t.status().equals("PAUSED")&&!(media(t.taskType())&&Set.of("FAILED","NEEDS_RECONCILIATION").contains(t.status()))) throw new LabException("OPERATION_CONFLICT", "当前状态不能恢复");
                if(media(t.taskType())&&sql.scalar(mapper.actionMediaOperationsSelect(new Object[]{id}), Integer.class)>0)throw new LabException("MEDIA_SUBMISSION_UNKNOWN","无原ID的未知提交禁止自动重购");
                if(media(t.taskType()))mapper.actionMediaOperationsWrite(new Object[]{id});
                yield "QUEUED";
            }
            case "cancel" -> {
                if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "MEDIA_READY").contains(t.status()))
                    throw new LabException("OPERATION_CONFLICT", "终态不能取消");
                yield "CANCELLED";
            }
            default -> throw LabException.invalid("只支持 pause/resume/cancel");
        };
        mapper.actionAiTasksWrite(new Object[]{state, id});
        if (state.equals("PAUSED"))
            mapper.actionTaskStepProgressWrite(new Object[]{id});
        else if (state.equals("QUEUED"))
            mapper.actionTaskStepProgressWrite2(new Object[]{id});
        else
            mapper.actionTaskStepProgressWrite3(new Object[]{id});
        return read(actor, id);
    }

    /**
     * 程序领取当前角色；attempt 仅统计异常租约恢复，模型和累计时长预算保持单调。
     */
    @Transactional
    public Optional<TaskLease> claim(String worker) { return claimInternal(worker,false); }

    /** 媒体查询等待不增加异常恢复次数，普通Worker不会消费媒体任务。 */
    @Transactional
    public Optional<TaskLease> claimMedia(String worker) { return claimInternal(worker,true); }

    /** 同一锁顺序串行状态转换；等待时只挑到期原ID查询，绝不重新生成。 */
    private Optional<TaskLease> claimInternal(String worker,boolean mediaQueue) {
        sql.scalar(mapper.claimInternalSystemControlSelect(new Object[]{}), Integer.class);
        mapper.claimInternalAiTasksWrite(new Object[]{});
        // 自动撤销与显式取消采用相同步骤状态，不留下误导性的“执行中”标记。
        mapper.claimInternalTaskStepProgressWrite(new Object[]{});
        // 正常暂停不消耗异常租约恢复次数；超限队列明确终止，不能永久停留 QUEUED。
        mapper.claimInternalAiTasksWrite2(new Object[]{});
        mapper.claimInternalTaskStepProgressWrite2(new Object[]{});
        var ids = sql.project(mapper.claimInternalAiTasksSelect(new Object[]{}, mediaQueue), (r, n) -> r.longValue(1));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        mapper.claimInternalAiTasksWrite3(new Object[]{worker, id});
        // 恢复只清理未成功步骤的执行标记，成功检查点和完成时间保持不变。
        mapper.claimInternalTaskStepProgressWrite3(new Object[]{id});
        return sql.project(mapper.claimInternalAiTasksSelect2(new Object[]{id}), (r, n) -> new TaskLease(task(r, n), decode(r.string("request_json"), TaskRequest.class), new UserContext(r.longValue("requester_user_id"), UserContext.Role.valueOf(r.string("role")), r.booleanValue("enabled"), r.longValue("permission_version"), r.booleanValue("password_change_required")), worker, r.longValue("fencing_token"))).stream().findFirst();
    }

    /**
     * 续租失败后执行者不能开始新的模型／工具步骤。
     */
    @Transactional
    public boolean renew(TaskLease lease) {
        try {
            valid(lease);
            mapper.renewAiTasksWrite(new Object[]{lease.task().taskId()});
            return true;
        } catch (LabException e) {
            return false;
        }
    }

    /** 先验证租约，再记录执行中；成功步骤不会因恢复被改回 RUNNING。 */
    @Transactional
    public void beginStep(TaskLease lease, String stepId) {
        valid(lease);
        if (!TaskProgress.stepIds().contains(stepId)) throw LabException.invalid("未知任务步骤");
        int changed = mapper.beginStepTaskStepProgressWrite(new Object[]{lease.task().taskId(), stepId});
        if (changed > 0) progressChanged(lease);
    }

    /** 准备阶段不是模型检查点；明确完成后才计入五步进度。 */
    @Transactional
    public void completePreparation(TaskLease lease) {
        valid(lease);
        int changed = mapper.completePreparationTaskStepProgressWrite(new Object[]{lease.task().taskId()});
        if (changed > 0) progressChanged(lease);
    }

    /** 调用方处于有效短事务内，进度变化递增版本用于客户端识别新事实。 */
    private void progressChanged(TaskLease lease) {
        mapper.progressChangedAiTasksWrite(new Object[]{lease.task().taskId()});
    }

    /**
     * 持久调用计数独立于可丢遥测，重启不恢复十次额度。
     */
    @Transactional
    public void reserveModelAttempt(TaskLease lease) {
        valid(lease);
        if (mapper.reserveModelAttemptAiTasksWrite(new Object[]{lease.task().taskId()}) != 1)
            throw new LabException("BUDGET_EXCEEDED", "持久模型尝试预算耗尽");
    }

    /**
     * 逻辑生成轮数与真实尝试分开计量，全部角色共享上限。
     */
    @Transactional
    public void reserveModelTurn(TaskLease lease) {
        valid(lease);
        if (mapper.reserveModelTurnAiTasksWrite(new Object[]{lease.task().taskId()}) != 1)
            throw new LabException("BUDGET_EXCEEDED", "持久模型轮数耗尽");
    }

    /**
     * 完成检查点唯一键确保恢复跳过已成功步骤。
     */
    @Transactional
    public void checkpoint(TaskLease lease, TaskCheckpoint checkpoint) {
        valid(lease);
        if (!List.of("research", "analysis", "report").contains(checkpoint.stepId()))
            throw LabException.invalid("未知模型检查点步骤");
        docs.verifySources(all(lease.actor()), checkpoint.sourceDependencies());
        int count = sql.scalar(mapper.checkpointTaskStepsSelect(new Object[]{lease.task().taskId(), checkpoint.stepId()}), Integer.class);
        if (count > 0) return;
        mapper.checkpointTaskStepsWrite(new Object[]{lease.task().taskId(), checkpoint.stepId(), checkpoint.content(), encode(checkpoint.sourceDependencies()), checkpoint.partial()});
        mapper.checkpointTaskStepProgressWrite(new Object[]{lease.task().taskId(), checkpoint.stepId()});
        mapper.checkpointAiTasksWrite(new Object[]{lease.task().taskId()});
    }

    /**
     * 恢复读取成功事实，来源已撤销则停止。
     */
    public List<TaskCheckpoint> checkpoints(TaskLease lease) {
        sql.actor(lease.actor(), false);
        var checkpoints = sql.project(mapper.checkpointsTaskStepsSelect(new Object[]{lease.task().taskId()}), (r, n) -> new TaskCheckpoint(r.string("step_id"), r.string("content"), sources(r.string("source_json")), r.booleanValue("partial")));
        for (var c : checkpoints) docs.verifySources(all(lease.actor()), c.sourceDependencies());
        return checkpoints;
    }

    /** 初始范围涵盖每一份所选文档；重复恢复不得重置已读页次或变更绑定版本。 */
    @Transactional
    public void initializeCoverage(TaskLease lease,List<DocumentCoverage> coverage) {
        valid(lease);
        if (coverage.size() != lease.request().documentIds().stream().distinct().count() || coverage.size() > 6)
            throw LabException.invalid("覆盖登记必须包含全部所选文档");
        var registered = new HashSet<Long>();
        for (var c : coverage) {
            if (!registered.add(c.documentId()) || !lease.request().documentIds().contains(c.documentId())) throw LabException.invalid("覆盖文档不合法");
            var content = docs.read(taskScope(lease),c.documentId()); var d = content.document();
            if (d.activeProcessingRevision() == null) throw new LabException("INDEX_NOT_READY","报告完整覆盖需先完成结构入库");
            if (c.documentVersion() != d.documentVersion() || c.processingRevision() != d.activeProcessingRevision()
                    || c.readStartOffset()!=0 || c.readEndOffset()!=0 || c.remainingStartOffset()!=0 || c.remainingEndOffset()!=content.text().length()
                    || c.completedPages()!=0 || c.complete()) throw new LabException("CONTEXT_MAPPING_INVALID","初始覆盖不是当前全文范围");
            int root = sql.scalar(mapper.initializeCoverageDocumentSectionsSelect(new Object[]{d.id(), d.documentVersion(), c.processingRevision(), c.sectionId(), content.text().length()}), Integer.class);
            if (root != 1) throw new LabException("CONTEXT_MAPPING_INVALID","全文根章节缺失");
            var existing = coverage(lease.task().taskId()).stream().filter(old -> old.documentId()==c.documentId()).findFirst();
            if (existing.isPresent()) {
                var old = existing.get();
                if (old.documentVersion()!=c.documentVersion() || old.processingRevision()!=c.processingRevision() || old.remainingEndOffset()!=c.remainingEndOffset())
                    throw new LabException("CONTEXT_VERSION_CONFLICT","恢复时覆盖版本已变化");
                continue;
            }
            mapper.initializeCoverageTaskDocumentCoverageWrite(new Object[]{lease.task().taskId(), c.documentId(), c.documentVersion(), c.processingRevision(), c.sectionId(), c.remainingEndOffset(), TextWindow.COUNT_SOURCE});
        }
        progressChanged(lease);
    }

    /** 页摘要与覆盖游标同事务，连续页次 CAS 且来源／版本／原文重新复核；不跨远程调用持锁。 */
    @Transactional
    public void checkpointPage(TaskLease lease,TaskPageCheckpoint checkpoint) {
        valid(lease); validatePage(lease,checkpoint);
        var page = checkpoint.page(); long taskId = lease.task().taskId();
        var previous = sql.project(mapper.checkpointPageTaskDocumentPagesSelect(new Object[]{taskId, page.documentId(), checkpoint.pageIndex()}), (r,n) -> new TaskPageCheckpoint(checkpoint.pageIndex(),decode(r.string(1),SectionPage.class),r.string(2),sources(r.string(3))));
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(checkpoint)) throw new LabException("OPERATION_CONFLICT","同页次已有不同成功事实");
            return;
        }
        int changed = mapper.checkpointPageTaskDocumentCoverageWrite(new Object[]{page.endOffset(), page.endOffset(), page.complete(), taskId, page.documentId(), page.documentVersion(), page.processingRevision(), checkpoint.pageIndex(), page.startOffset()});
        if (changed != 1) throw new LabException("CONTEXT_VERSION_CONFLICT","页次或覆盖游标不连续");
        mapper.checkpointPageTaskDocumentPagesWrite(new Object[]{taskId, page.documentId(), checkpoint.pageIndex(), encode(page), checkpoint.summary(), encode(checkpoint.sourceDependencies())});
        progressChanged(lease);
    }

    /** 成功页次只能在当前租约和来源下复用；解析数据损坏或代次变化明确停止。 */
    @Transactional
    public List<TaskPageCheckpoint> pages(TaskLease lease) {
        valid(lease);
        var pages = sql.project(mapper.pagesTaskDocumentPagesSelect(new Object[]{lease.task().taskId()}), (r,n) -> new TaskPageCheckpoint(r.intValue("page_index"),decode(r.string("page_json"),SectionPage.class),r.string("summary"),sources(r.string("source_json"))));
        for (var page : pages) validatePage(lease,page);
        for (var c : coverage(lease.task().taskId())) {
            // 未读文档也绑定初始化时的版本／代次，不能在恢复途中悄悄换成新版剩余范围。
            var current=docs.read(taskScope(lease),c.documentId()).document();
            if (current.documentVersion()!=c.documentVersion() || !Objects.equals(current.activeProcessingRevision(),c.processingRevision()))
                throw new LabException("CONTEXT_VERSION_CONFLICT","覆盖来源版本或代次已变化");
            var entries = pages.stream().filter(p -> p.page().documentId()==c.documentId()).toList();
            int cursor=0,index=0;
            for (var entry : entries) {
                if (entry.pageIndex()!=index++ || entry.page().startOffset()!=cursor) throw new LabException("CONTEXT_MAPPING_INVALID","成功页次不连续");
                cursor=entry.page().endOffset();
            }
            if (index!=c.completedPages() || cursor!=c.readEndOffset()) throw new LabException("CONTEXT_MAPPING_INVALID","检查点与覆盖事实不一致");
        }
        return pages;
    }

    /** 分页前查询持久轮数，为剩余角色预留预算；查询本身不重置计数。 */
    @Transactional
    public int remainingModelTurns(TaskLease lease) {
        valid(lease);
        return (media(lease.request().taskType())?24:6)-sql.scalar(mapper.remainingModelTurnsAiTasksSelect(new Object[]{lease.task().taskId()}), Integer.class);
    }

    /** 当前租约下恢复唯一计划；已成功节点仍由原检查点复用。 */
    @Transactional
    public Optional<TaskPlan> plan(TaskLease lease) {
        valid(lease);
        return readPlan(lease.actor(), lease.task().taskId()).map(TaskPlanSnapshot::plan);
    }

    /** 本人查询计划，JSON摘要与hash不一致时拒绝恢复，不猜测原计划。 */
    @Transactional(readOnly = true)
    public Optional<TaskPlanSnapshot> readPlan(UserContext actor, long taskId) {
        read(actor, taskId);
        return sql.project(mapper.readPlanTaskPlansSelect(new Object[]{taskId}), (r, n) -> {
            var plan = decode(r.string("plan_json"), TaskPlan.class);
            if (!SqlSupport.hash(encode(plan)).equals(r.string("plan_hash")))
                throw new LabException("CONTEXT_MAPPING_INVALID", "计划摘要不匹配");
            return new TaskPlanSnapshot(plan, r.string("plan_hash"), r.string("agent_version"), r.string("model_id"), r.string("policy_version"));
        }).stream().findFirst();
    }

    /** 已校验计划不可覆盖；fencing／用户锁与任务写事务共用，远程生成在本方法外。 */
    @Transactional
    public void savePlan(TaskLease lease, TaskPlan plan, String modelId, String policyVersion) {
        valid(lease);
        if (!lease.request().strategy().equals("PLANNED") || !"plan-s05-v1".equals(plan.version())
                || plan.steps().size() != 3 || modelId == null || modelId.length() > 64 || policyVersion == null || policyVersion.length() > 128)
            throw LabException.invalid("计划登记参数不合法");
        var previous = readPlan(lease.actor(), lease.task().taskId());
        if (previous.isPresent()) {
            if (!previous.get().plan().equals(plan)) throw new LabException("OPERATION_CONFLICT", "已登记计划不可覆盖");
            return;
        }
        String encoded = encode(plan);
        mapper.savePlanTaskPlansWrite(new Object[]{lease.task().taskId(), plan.version(), SqlSupport.hash(encoded), encoded, "s05-v1", modelId, policyVersion});
        progressChanged(lease);
    }

    /** 工具调用逐次持久消费，任何角色或恢复都不能重获额度。 */
    @Transactional
    public void reserveToolCall(TaskLease lease) {
        valid(lease);
        if (mapper.reserveToolCallAiTasksWrite(new Object[]{lease.task().taskId()}) != 1)
            throw new LabException("BUDGET_EXCEEDED", "持久工具预算耗尽");
    }

    /** 全任务只有一次结构修复，崩溃后也不能再次取得。 */
    @Transactional
    public void reserveModelRepair(TaskLease lease) {
        valid(lease);
        if (mapper.reserveModelRepairAiTasksWrite(new Object[]{lease.task().taskId()}) != 1)
            throw new LabException("MODEL_REPAIR_EXHAUSTED", "持久结构修复预算耗尽");
    }

    /** 每页都指当前合法原文与真实章节，未送模范围不能伪装成已读成功。 */
    private void validatePage(TaskLease lease,TaskPageCheckpoint checkpoint) {
        var p=checkpoint.page();
        if (checkpoint.pageIndex()<0 || checkpoint.pageIndex()>3 || !lease.request().documentIds().contains(p.documentId())
                || checkpoint.summary()==null || checkpoint.summary().isBlank() || TextWindow.count(checkpoint.summary())>1000) throw LabException.invalid("分页检查点超限");
        var content=docs.read(taskScope(lease),p.documentId()); var d=content.document();
        if (d.documentVersion()!=p.documentVersion() || !Objects.equals(d.activeProcessingRevision(),p.processingRevision())) throw new LabException("CONTEXT_VERSION_CONFLICT","分页来源版本或代次已变化");
        if (!TextWindow.boundary(content.text(),p.startOffset()) || !TextWindow.boundary(content.text(),p.endOffset()) || p.startOffset()>=p.endOffset()
                || !content.text().substring(p.startOffset(),p.endOffset()).equals(p.text()) || TextWindow.count(p.text())>4000
                || p.tokenCount()!=TextWindow.count(p.text()) || !TextWindow.COUNT_SOURCE.equals(p.countSource())
                || p.remainingStartOffset()!=p.endOffset() || p.remainingEndOffset()!=content.text().length() || p.complete()!=(p.endOffset()==content.text().length())
                || !Objects.equals(p.nextOffset(),p.complete()?null:p.endOffset())) throw new LabException("CONTEXT_MAPPING_INVALID","分页原文映射不合法");
        int section=sql.scalar(mapper.validatePageDocumentSectionsSelect(new Object[]{p.documentId(), p.documentVersion(), p.processingRevision(), p.sectionId(), p.startOffset(), p.endOffset()}), Integer.class);
        if (section!=1 || !checkpoint.sourceDependencies().contains(new SourceDependency(d.knowledgeBaseId(),d.id(),d.documentVersion()))) throw new LabException("CONTEXT_MAPPING_INVALID","分页章节或来源缺失");
        docs.verifySources(all(lease.actor()),checkpoint.sourceDependencies());
        if (!checkpoint.sourceDependencies().containsAll(content.sourceDependencies())) throw new LabException("CONTEXT_MAPPING_INVALID","分页丢失派生来源约束");
    }

    /** 当前任务原始 Scope 只可缩小读取，管理员降级不沿用旧 ALL。 */
    private AuthorizedKnowledgeScope taskScope(TaskLease lease) {
        var request=lease.request().scope()==null?ScopeRequest.self():lease.request().scope();
        return new AuthorizedKnowledgeScope(lease.actor(),request.mode(),request.knowledgeBaseIds(),request.ownerUserId(),Instant.now());
    }

    /** 覆盖只包含位置和计数，不公开摘要草稿／提示词；历史任务为空表示未登记。 */
    private List<DocumentCoverage> coverage(long taskId) {
        return sql.project(mapper.coverageTaskDocumentCoverageSelect(new Object[]{taskId}), (r,n) -> new DocumentCoverage(r.longValue("document_id"),r.intValue("document_version"),r.longValue("processing_revision"),r.string("section_id"),r.intValue("completed_pages"),r.intValue("read_start"),r.intValue("read_end"),r.intValue("remaining_start"),r.intValue("remaining_end"),r.booleanValue("complete"),r.string("count_source")));
    }

    /**
     * 产物发布与任务终态同事务，取消先提交时无法发布。
     */
    @Transactional
    public void publish(TaskLease lease, TaskCheckpoint report) {
        valid(lease);
        // 页来源／代次在发布短事务再次核验，已读事实不能套用到新处理批次。
        pages(lease);
        if (coverage(lease.task().taskId()).stream().anyMatch(c -> !c.complete()) && !report.partial())
            throw new LabException("CONTEXT_MAPPING_INVALID","仍有未读范围却声明完整发布");
        docs.verifySources(all(lease.actor()), report.sourceDependencies());
        if (report.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new LabException("BUDGET_EXCEEDED", "报告超过 1 MB");
        long id = sql.insert(command -> mapper.publishArtifactsInsert(command), lease.actor().userId(), lease.task().taskId(), report.content(), encode(report.sourceDependencies()), SqlSupport.hash(report.content()));
        // 发布事实与可下载产物同事务提交，避免在报告校验/授权完成之前显示 100%。
        mapper.publishTaskStepProgressWrite(new Object[]{lease.task().taskId()});
        mapper.publishAiTasksWrite(new Object[]{report.partial() ? "PARTIAL" : "SUCCEEDED", id, lease.task().taskId()});
    }

    /**
     * 旧执行者不覆盖新状态；真实失败明确终止，不无限自动重试模型。
     */
    @Transactional
    public void fail(TaskLease lease, String code) {
        try {
            valid(lease);
        } catch (LabException e) {
            return;
        }
        mapper.failAiTasksWrite(new Object[]{code, lease.task().taskId()});
        mapper.failTaskStepProgressWrite(new Object[]{code, lease.task().taskId()});
    }

    /**
     * 私人下载同时要求任务发布态和当前全部来源仍可读。
     */
    public ArtifactSnapshot artifact(UserContext actor, long id) {
        sql.actor(actor, false);
        var result = sql.project(mapper.artifactArtifactsSelect(new Object[]{id, actor.userId()}), (r, n) -> new ArtifactSnapshot(r.longValue("id"), actor.userId(), r.longValue("task_id"), r.string("filename"), r.string("mime"), r.string("content"), sources(r.string("source_json")),r.string("kind"),(Long)r.value("byte_size"),r.string("checksum"),r.string("storage_key"),r.string("media_operation_id"),r.intValue("revision"),(Integer)r.value("preview_version"))).stream().findFirst().orElseThrow(LabException::denied);
        docs.verifySources(all(actor), result.sourceDependencies());
        return result;
    }

    /** 文件存储只有正式装配才注入；历史纯文本测试构造继续兼容。 */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void files(MediaFilePort files) { this.mediaFiles = files; }
    private MediaFilePort mediaFiles;
    /** 下载先重核本人／来源，再检查真实文件摘要；Markdown沿用原字节内容。 */
    public byte[] bytes(UserContext actor,long id) {
        var a=artifact(actor,id);
        if (a.storageKey()==null) return a.content().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (mediaFiles==null) throw new LabException("MEDIA_VALIDATION_FAILED","受控文件存储不可用");
        var bytes=mediaFiles.read(new Media.FileFact(a.storageKey(),a.mime(),a.size(),a.checksum()));
        artifact(actor,id); return bytes;
    }
    /** 任务类型决定独立预算，不改变普通报告额度。 */
    private boolean media(String type) { return Set.of("NOTES_PPT","NOTES_VIDEO").contains(type); }

    /**
     * fencing/worker/租约/用户/工作流/累计期限全都必须满足。
     */
    void valid(TaskLease lease) {
        sql.actor(lease.actor(), true);
        int count = sql.scalar(mapper.validAiTasksSelect(new Object[]{lease.task().taskId(), lease.actor().userId(), lease.workerId(), lease.fencingToken()}), Integer.class);
        if (count != 1) throw new LabException("STALE_EXECUTION", "任务执行权或累计期限已失效");
    }

    /**
     * 不返回内部 lease/worker/私有文件路径。
     */
    private TaskSnapshot task(SqlRow r, int n) {
        long id = r.longValue("id");
        var steps = sql.project(mapper.taskTaskStepProgressSelect(new Object[]{id}), (step, row) ->
                new TaskStepSnapshot(step.string("step_id"), TaskProgress.label(step.string("step_id")),
                        step.string("status"), instant(step, "started_at"), instant(step, "completed_at"), step.string("error_code")));
        if (steps.size() != TaskProgress.stepIds().size()) throw new IllegalStateException("任务步骤进度不完整");
        Instant now = instant(r, "server_now"), leaseUntil = instant(r, "lease_until"), claimedAt = instant(r, "claimed_at");
        boolean leaseActive = leaseUntil != null && leaseUntil.isAfter(now);
        // 自动失败/撤销的历史行可能仍有 claimed_at，终止后用更新时间冻结计时。
        Instant measuredAt = r.string("status").equals("RUNNING") ? now : instant(r, "updated_at");
        long elapsed = r.longValue("total_execution_seconds") + (claimedAt == null || measuredAt == null ? 0
                : Math.max(0, Duration.between(claimedAt, measuredAt).getSeconds()));
        var progress = media(r.string("task_type")) ? TaskProgress.media(r.string("status"), r.string("media_phase"), mediaWorkerEnabled, leaseActive, steps, instant(r,"started_at"), instant(r,"updated_at"), instant(r,"heartbeat_at"), elapsed) : TaskProgress.from(r.string("status"), workerEnabled, leaseActive, steps,
                instant(r, "started_at"), instant(r, "updated_at"), instant(r, "heartbeat_at"), elapsed);
        return new TaskSnapshot(id, r.longValue("requester_user_id"), r.string("task_type"), r.string("status"), r.longValue("state_version"), r.intValue("model_attempts"), r.intValue("completed_steps"), r.string("error_code"), (Long) r.value("artifact_id"), progress,coverage(id));
    }

    /** JDBC 使用 UTC 时区转换 TIMESTAMP，历史缺失时间保持 null。 */
    private Instant instant(SqlRow row, String column) {
        Timestamp timestamp = row.timestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 原始来源复核按当前角色，不能因产物本人所有而绕过。
     */
    private AuthorizedKnowledgeScope all(UserContext actor) {
        return new AuthorizedKnowledgeScope(actor, actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF, List.of(), null, Instant.now());
    }

    /**
     * 固定 DTO JSON 序列化，无 Java 反序列化。
     */
    private String encode(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 仅使用明确的请求类型反序列化。
     */
    private <T> T decode(String s, Class<T> type) {
        try {
            return json.readValue(s, type);
        } catch (Exception e) {
            throw new LabException("INVALID_ARGUMENTS", "任务请求数据损坏");
        }
    }

    /**
     * 来源只解析固定列表类型。
     */
    private List<SourceDependency> sources(String s) {
        try {
            return json.readValue(s, new TypeReference<List<SourceDependency>>() {
            });
        } catch (Exception e) {
            throw new LabException("CONTEXT_MAPPING_INVALID", "来源数据损坏");
        }
    }
}
