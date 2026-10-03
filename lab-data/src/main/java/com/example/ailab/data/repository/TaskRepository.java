package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * 短事务任务状态机，外部模型与报告生成均在事务外。
 */
@Repository
public class TaskRepository implements TaskStorePort, ArtifactStorePort {
    private final SqlSupport sql;
    private final DocumentSqlRepository docs;
    private final ObjectMapper json = new ObjectMapper();
    private final boolean workerEnabled;

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
        this.sql = sql;
        this.docs = docs;
        this.workerEnabled = workerEnabled;
    }

    /**
     * 创建／参数 hash／用户命名空间去重在一个事务内。
     */
    @Transactional
    public TaskSnapshot create(UserContext actor, TaskRequest request) {
        sql.actor(actor, true);
        String serialized = encode(new TaskRequest(request.taskType(), request.topic(), request.scope(), request.documentIds().stream().distinct().sorted().toList(), request.idempotencyKey())), hash = SqlSupport.hash(serialized);
        var old = sql.jdbc.query("SELECT request_hash,resource_id FROM request_deduplications WHERE actor_user_id=? AND namespace='TASK_CREATE' AND request_key=?", (r, n) -> Map.entry(r.getString(1), r.getLong(2)), actor.userId(), request.idempotencyKey());
        if (!old.isEmpty()) {
            if (!old.get(0).getKey().equals(hash)) throw new LabException("OPERATION_CONFLICT", "相同任务键参数不同");
            return read(actor, old.get(0).getValue());
        }
        if (sql.jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks WHERE requester_user_id=? AND status NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')", Long.class, actor.userId()) >= 20)
            throw new LabException("RATE_LIMITED", "未完成任务数量超过限额");
        long id = sql.insert("INSERT INTO ai_tasks(requester_user_id,task_type,request_json,request_hash) VALUES(?,?,?,?)", actor.userId(), request.taskType(), serialized, hash);
        // 与任务创建同事务初始化步骤，202 返回时就有完整的待执行列表。
        for (String step : TaskProgress.stepIds())
            sql.jdbc.update("INSERT INTO task_step_progress(task_id,step_id) VALUES(?,?)", id, step);
        sql.jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'TASK_CREATE',?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY))", actor.userId(), request.idempotencyKey(), hash, id);
        return read(actor, id);
    }

    /**
     * 任何角色都必须是请求者。
     */
    @Transactional(readOnly = true)
    public TaskSnapshot read(UserContext actor, long id) {
        sql.actor(actor, false);
        return sql.jdbc.query("SELECT t.*,CURRENT_TIMESTAMP(6) AS server_now FROM ai_tasks t WHERE id=? AND requester_user_id=?", this::task, id, actor.userId()).stream().findFirst().orElseThrow(LabException::denied);
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
                if (!Set.of("QUEUED", "RUNNING").contains(t.status()))
                    throw new LabException("OPERATION_CONFLICT", "当前状态不能暂停");
                yield "PAUSED";
            }
            case "resume" -> {
                if (!t.status().equals("PAUSED")) throw new LabException("OPERATION_CONFLICT", "仅暂停任务可以恢复");
                yield "QUEUED";
            }
            case "cancel" -> {
                if (Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(t.status()))
                    throw new LabException("OPERATION_CONFLICT", "终态不能取消");
                yield "CANCELLED";
            }
            default -> throw LabException.invalid("只支持 pause/resume/cancel");
        };
        sql.jdbc.update("UPDATE ai_tasks SET status=?,state_version=state_version+1,fencing_token=fencing_token+1,total_execution_seconds=total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))),claimed_at=NULL,lease_until=NULL,worker_id=NULL WHERE id=?", state, id);
        if (state.equals("PAUSED"))
            sql.jdbc.update("UPDATE task_step_progress SET status='PAUSED' WHERE task_id=? AND status='RUNNING'", id);
        else if (state.equals("QUEUED"))
            sql.jdbc.update("UPDATE task_step_progress SET status='PENDING',started_at=NULL,error_code=NULL WHERE task_id=? AND status='PAUSED'", id);
        else
            sql.jdbc.update("UPDATE task_step_progress SET status='CANCELLED' WHERE task_id=? AND status<>'SUCCEEDED'", id);
        return read(actor, id);
    }

    /**
     * 程序领取当前角色；attempt 仅统计异常租约恢复，模型和累计时长预算保持单调。
     */
    @Transactional
    public Optional<TaskLease> claim(String worker) {
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE", Integer.class);
        sql.jdbc.update("UPDATE ai_tasks t JOIN users u ON u.id=t.requester_user_id SET t.status=\'CANCELLED\',t.state_version=t.state_version+1,t.fencing_token=t.fencing_token+1,t.worker_id=NULL,t.lease_until=NULL WHERE u.enabled=FALSE AND t.status IN (\'QUEUED\',\'RUNNING\',\'PAUSED\')");
        // 自动撤销与显式取消采用相同步骤状态，不留下误导性的“执行中”标记。
        sql.jdbc.update("UPDATE task_step_progress p JOIN ai_tasks t ON t.id=p.task_id SET p.status='CANCELLED' WHERE t.status='CANCELLED' AND p.status NOT IN ('SUCCEEDED','CANCELLED')");
        // 正常暂停不消耗异常租约恢复次数；超限队列明确终止，不能永久停留 QUEUED。
        sql.jdbc.update("UPDATE ai_tasks SET status='FAILED',error_code='BUDGET_EXCEEDED',fencing_token=fencing_token+1,state_version=state_version+1,worker_id=NULL,lease_until=NULL WHERE status IN ('QUEUED','RUNNING') AND (total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)))>=1200 OR status='RUNNING' AND attempt>=3 AND lease_until<CURRENT_TIMESTAMP(6) OR (model_attempts>=10 OR model_turns>=6) AND completed_steps<3 AND (status='QUEUED' OR lease_until<CURRENT_TIMESTAMP(6)))");
        sql.jdbc.update("UPDATE task_step_progress p JOIN ai_tasks t ON t.id=p.task_id SET p.status='FAILED',p.error_code=t.error_code WHERE t.status='FAILED' AND p.status IN ('RUNNING','PAUSED')");
        var ids = sql.jdbc.query("SELECT t.id FROM ai_tasks t JOIN users u ON u.id=t.requester_user_id WHERE u.enabled=TRUE AND u.password_change_required=FALSE AND (t.status='QUEUED' OR t.status='RUNNING' AND t.attempt<3 AND t.lease_until<CURRENT_TIMESTAMP(6)) ORDER BY t.id LIMIT 1 FOR UPDATE SKIP LOCKED", (r, n) -> r.getLong(1));
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.get(0);
        sql.jdbc.update("UPDATE ai_tasks SET attempt=attempt+IF(status='RUNNING',1,0),status='RUNNING',state_version=state_version+1,fencing_token=fencing_token+1,worker_id=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),total_execution_seconds=total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))),claimed_at=CURRENT_TIMESTAMP(6),started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=?", worker, id);
        // 恢复只清理未成功步骤的执行标记，成功检查点和完成时间保持不变。
        sql.jdbc.update("UPDATE task_step_progress SET status='PENDING',started_at=NULL,error_code=NULL WHERE task_id=? AND status IN ('RUNNING','PAUSED')", id);
        return sql.jdbc.query("SELECT t.*,CURRENT_TIMESTAMP(6) AS server_now,u.role,u.permission_version,u.enabled,u.password_change_required FROM ai_tasks t JOIN users u ON u.id=t.requester_user_id WHERE t.id=?", (r, n) -> new TaskLease(task(r, n), decode(r.getString("request_json"), TaskRequest.class), new UserContext(r.getLong("requester_user_id"), UserContext.Role.valueOf(r.getString("role")), r.getBoolean("enabled"), r.getLong("permission_version"), r.getBoolean("password_change_required")), worker, r.getLong("fencing_token")), id).stream().findFirst();
    }

    /**
     * 续租失败后执行者不能开始新的模型／工具步骤。
     */
    @Transactional
    public boolean renew(TaskLease lease) {
        try {
            valid(lease);
            sql.jdbc.update("UPDATE ai_tasks SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=?", lease.task().taskId());
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
        int changed = sql.jdbc.update("UPDATE task_step_progress SET status='RUNNING',started_at=CURRENT_TIMESTAMP(6),completed_at=NULL,error_code=NULL WHERE task_id=? AND step_id=? AND status='PENDING'", lease.task().taskId(), stepId);
        if (changed > 0) progressChanged(lease);
    }

    /** 准备阶段不是模型检查点；明确完成后才计入五步进度。 */
    @Transactional
    public void completePreparation(TaskLease lease) {
        valid(lease);
        int changed = sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND step_id='prepare' AND status='RUNNING'", lease.task().taskId());
        if (changed > 0) progressChanged(lease);
    }

    /** 调用方处于有效短事务内，进度变化递增版本用于客户端识别新事实。 */
    private void progressChanged(TaskLease lease) {
        sql.jdbc.update("UPDATE ai_tasks SET state_version=state_version+1 WHERE id=?", lease.task().taskId());
    }

    /**
     * 持久调用计数独立于可丢遥测，重启不恢复十次额度。
     */
    @Transactional
    public void reserveModelAttempt(TaskLease lease) {
        valid(lease);
        if (sql.jdbc.update("UPDATE ai_tasks SET model_attempts=model_attempts+1 WHERE id=? AND model_attempts<10", lease.task().taskId()) != 1)
            throw new LabException("BUDGET_EXCEEDED", "持久模型尝试预算耗尽");
    }

    /**
     * 逻辑生成轮数与真实尝试分开计量，全部角色共享上限。
     */
    @Transactional
    public void reserveModelTurn(TaskLease lease) {
        valid(lease);
        if (sql.jdbc.update("UPDATE ai_tasks SET model_turns=model_turns+1 WHERE id=? AND model_turns<6", lease.task().taskId()) != 1)
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
        int count = sql.jdbc.queryForObject("SELECT COUNT(*) FROM task_steps WHERE task_id=? AND step_id=?", Integer.class, lease.task().taskId(), checkpoint.stepId());
        if (count > 0) return;
        sql.jdbc.update("INSERT INTO task_steps(task_id,step_id,content,source_json,partial) VALUES(?,?,?,?,?)", lease.task().taskId(), checkpoint.stepId(), checkpoint.content(), encode(checkpoint.sourceDependencies()), checkpoint.partial());
        sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6),error_code=NULL WHERE task_id=? AND step_id=?", lease.task().taskId(), checkpoint.stepId());
        sql.jdbc.update("UPDATE ai_tasks SET completed_steps=completed_steps+1,state_version=state_version+1 WHERE id=?", lease.task().taskId());
    }

    /**
     * 恢复读取成功事实，来源已撤销则停止。
     */
    public List<TaskCheckpoint> checkpoints(TaskLease lease) {
        sql.actor(lease.actor(), false);
        var checkpoints = sql.jdbc.query("SELECT * FROM task_steps WHERE task_id=? ORDER BY step_id", (r, n) -> new TaskCheckpoint(r.getString("step_id"), r.getString("content"), sources(r.getString("source_json")), r.getBoolean("partial")), lease.task().taskId());
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
            int root = sql.jdbc.queryForObject("SELECT COUNT(*) FROM document_sections WHERE document_id=? AND document_version=? AND processing_revision=? AND section_id=? AND ordinal=0 AND start_offset=0 AND end_offset=?",Integer.class,d.id(),d.documentVersion(),c.processingRevision(),c.sectionId(),content.text().length());
            if (root != 1) throw new LabException("CONTEXT_MAPPING_INVALID","全文根章节缺失");
            var existing = coverage(lease.task().taskId()).stream().filter(old -> old.documentId()==c.documentId()).findFirst();
            if (existing.isPresent()) {
                var old = existing.get();
                if (old.documentVersion()!=c.documentVersion() || old.processingRevision()!=c.processingRevision() || old.remainingEndOffset()!=c.remainingEndOffset())
                    throw new LabException("CONTEXT_VERSION_CONFLICT","恢复时覆盖版本已变化");
                continue;
            }
            sql.jdbc.update("INSERT INTO task_document_coverage(task_id,document_id,document_version,processing_revision,section_id,read_start,read_end,remaining_start,remaining_end,count_source) VALUES(?,?,?,?,?,0,0,0,?,?)",
                    lease.task().taskId(),c.documentId(),c.documentVersion(),c.processingRevision(),c.sectionId(),c.remainingEndOffset(),TextWindow.COUNT_SOURCE);
        }
        progressChanged(lease);
    }

    /** 页摘要与覆盖游标同事务，连续页次 CAS 且来源／版本／原文重新复核；不跨远程调用持锁。 */
    @Transactional
    public void checkpointPage(TaskLease lease,TaskPageCheckpoint checkpoint) {
        valid(lease); validatePage(lease,checkpoint);
        var page = checkpoint.page(); long taskId = lease.task().taskId();
        var previous = sql.jdbc.query("SELECT page_json,summary,source_json FROM task_document_pages WHERE task_id=? AND document_id=? AND page_index=?",(r,n) -> new TaskPageCheckpoint(checkpoint.pageIndex(),decode(r.getString(1),SectionPage.class),r.getString(2),sources(r.getString(3))),taskId,page.documentId(),checkpoint.pageIndex());
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(checkpoint)) throw new LabException("OPERATION_CONFLICT","同页次已有不同成功事实");
            return;
        }
        int changed = sql.jdbc.update("UPDATE task_document_coverage SET completed_pages=completed_pages+1,read_end=?,remaining_start=?,complete=? WHERE task_id=? AND document_id=? AND document_version=? AND processing_revision=? AND completed_pages=? AND read_end=? AND complete=FALSE",
                page.endOffset(),page.endOffset(),page.complete(),taskId,page.documentId(),page.documentVersion(),page.processingRevision(),checkpoint.pageIndex(),page.startOffset());
        if (changed != 1) throw new LabException("CONTEXT_VERSION_CONFLICT","页次或覆盖游标不连续");
        sql.jdbc.update("INSERT INTO task_document_pages(task_id,document_id,page_index,page_json,summary,source_json) VALUES(?,?,?,?,?,?)",taskId,page.documentId(),checkpoint.pageIndex(),encode(page),checkpoint.summary(),encode(checkpoint.sourceDependencies()));
        progressChanged(lease);
    }

    /** 成功页次只能在当前租约和来源下复用；解析数据损坏或代次变化明确停止。 */
    @Transactional
    public List<TaskPageCheckpoint> pages(TaskLease lease) {
        valid(lease);
        var pages = sql.jdbc.query("SELECT * FROM task_document_pages WHERE task_id=? ORDER BY document_id,page_index",(r,n) -> new TaskPageCheckpoint(r.getInt("page_index"),decode(r.getString("page_json"),SectionPage.class),r.getString("summary"),sources(r.getString("source_json"))),lease.task().taskId());
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
        return 6-sql.jdbc.queryForObject("SELECT model_turns FROM ai_tasks WHERE id=?",Integer.class,lease.task().taskId());
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
        int section=sql.jdbc.queryForObject("SELECT COUNT(*) FROM document_sections WHERE document_id=? AND document_version=? AND processing_revision=? AND section_id=? AND start_offset<=? AND end_offset>=?",Integer.class,p.documentId(),p.documentVersion(),p.processingRevision(),p.sectionId(),p.startOffset(),p.endOffset());
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
        return sql.jdbc.query("SELECT * FROM task_document_coverage WHERE task_id=? ORDER BY document_id",(r,n) -> new DocumentCoverage(r.getLong("document_id"),r.getInt("document_version"),r.getLong("processing_revision"),r.getString("section_id"),r.getInt("completed_pages"),r.getInt("read_start"),r.getInt("read_end"),r.getInt("remaining_start"),r.getInt("remaining_end"),r.getBoolean("complete"),r.getString("count_source")),taskId);
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
        long id = sql.insert("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum) VALUES(?,?,'report.md','text/markdown',?,?,?)", lease.actor().userId(), lease.task().taskId(), report.content(), encode(report.sourceDependencies()), SqlSupport.hash(report.content()));
        // 发布事实与可下载产物同事务提交，避免在报告校验/授权完成之前显示 100%。
        sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6),error_code=NULL WHERE task_id=? AND step_id='publish'", lease.task().taskId());
        sql.jdbc.update("UPDATE ai_tasks SET status=?,artifact_id=?,state_version=state_version+1,lease_until=NULL,worker_id=NULL,total_execution_seconds=total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)),claimed_at=NULL WHERE id=?", report.partial() ? "PARTIAL" : "SUCCEEDED", id, lease.task().taskId());
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
        sql.jdbc.update("UPDATE ai_tasks SET status='FAILED',error_code=?,state_version=state_version+1,lease_until=NULL,worker_id=NULL,total_execution_seconds=total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)),claimed_at=NULL WHERE id=?", code, lease.task().taskId());
        sql.jdbc.update("UPDATE task_step_progress SET status='FAILED',error_code=? WHERE task_id=? AND status='RUNNING'", code, lease.task().taskId());
    }

    /**
     * 私人下载同时要求任务发布态和当前全部来源仍可读。
     */
    public ArtifactSnapshot artifact(UserContext actor, long id) {
        sql.actor(actor, false);
        var result = sql.jdbc.query("SELECT a.* FROM artifacts a JOIN ai_tasks t ON t.id=a.task_id WHERE a.id=? AND a.requester_user_id=? AND a.published=TRUE AND t.status IN ('SUCCEEDED','PARTIAL')", (r, n) -> new ArtifactSnapshot(r.getLong("id"), actor.userId(), r.getLong("task_id"), r.getString("filename"), r.getString("mime"), r.getString("content"), sources(r.getString("source_json"))), id, actor.userId()).stream().findFirst().orElseThrow(LabException::denied);
        docs.verifySources(all(actor), result.sourceDependencies());
        return result;
    }

    /**
     * fencing/worker/租约/用户/工作流/累计期限全都必须满足。
     */
    private void valid(TaskLease lease) {
        sql.actor(lease.actor(), true);
        int count = sql.jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks WHERE id=? AND requester_user_id=? AND worker_id=? AND fencing_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6) AND workflow_version='report-v1' AND total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))<1200", Integer.class, lease.task().taskId(), lease.actor().userId(), lease.workerId(), lease.fencingToken());
        if (count != 1) throw new LabException("STALE_EXECUTION", "任务执行权或累计期限已失效");
    }

    /**
     * 不返回内部 lease/worker/私有文件路径。
     */
    private TaskSnapshot task(ResultSet r, int n) throws SQLException {
        long id = r.getLong("id");
        var steps = sql.jdbc.query("SELECT * FROM task_step_progress WHERE task_id=? ORDER BY FIELD(step_id,'prepare','research','analysis','report','publish')", (step, row) ->
                new TaskStepSnapshot(step.getString("step_id"), TaskProgress.label(step.getString("step_id")),
                        step.getString("status"), instant(step, "started_at"), instant(step, "completed_at"), step.getString("error_code")), id);
        if (steps.size() != TaskProgress.stepIds().size()) throw new IllegalStateException("任务步骤进度不完整");
        Instant now = instant(r, "server_now"), leaseUntil = instant(r, "lease_until"), claimedAt = instant(r, "claimed_at");
        boolean leaseActive = leaseUntil != null && leaseUntil.isAfter(now);
        // 自动失败/撤销的历史行可能仍有 claimed_at，终止后用更新时间冻结计时。
        Instant measuredAt = r.getString("status").equals("RUNNING") ? now : instant(r, "updated_at");
        long elapsed = r.getLong("total_execution_seconds") + (claimedAt == null || measuredAt == null ? 0
                : Math.max(0, Duration.between(claimedAt, measuredAt).getSeconds()));
        var progress = TaskProgress.from(r.getString("status"), workerEnabled, leaseActive, steps,
                instant(r, "started_at"), instant(r, "updated_at"), instant(r, "heartbeat_at"), elapsed);
        return new TaskSnapshot(id, r.getLong("requester_user_id"), r.getString("task_type"), r.getString("status"), r.getLong("state_version"), r.getInt("model_attempts"), r.getInt("completed_steps"), r.getString("error_code"), (Long) r.getObject("artifact_id"), progress,coverage(id));
    }

    /** JDBC 使用 UTC 时区转换 TIMESTAMP，历史缺失时间保持 null。 */
    private Instant instant(ResultSet row, String column) throws SQLException {
        Timestamp timestamp = row.getTimestamp(column);
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
