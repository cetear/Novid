package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.PrivateResourceMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;

/**
 * 记忆与运行均固定 actor 过滤，不引入管理员全局旁路。
 */
@Repository
public class PrivateResourceRepository implements MemoryStorePort, TraceRecordPort {
    private final PrivateResourceMapper mapper;
    private final SqlSupport sql;
    private final com.fasterxml.jackson.databind.ObjectMapper traceJson = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    /**
     * 装配内部数据访问组件。
     */
    public PrivateResourceRepository(SqlSupport sql) {
        this.sql = sql; this.mapper = sql.mapper(PrivateResourceMapper.class);
    }

    /**
     * 返回本人尚未删除的偏好。
     */
    public List<MemorySnapshot> list(UserContext actor) {
        sql.actor(actor, false);
        return sql.project(mapper.listProfileMemoriesSelect(new Object[]{actor.userId()}), this::memory);
    }

    /**
     * 用户明确命令保存偏好，限定每人 100 条。
     */
    @Transactional
    public MemorySnapshot create(UserContext actor, String content) {
        sql.actor(actor, true);
        if (sql.scalar(mapper.createProfileMemoriesSelect(new Object[]{actor.userId()}), Long.class) >= 100)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "最多保存 100 条偏好");
        long id = sql.insert(command -> mapper.createProfileMemoriesInsert(command), actor.userId(), content);
        resetSessionContexts(actor);
        return new MemorySnapshot(id, actor.userId(), content, 1);
    }

    /**
     * 本人版本 CAS 更正。
     */
    @Transactional
    public MemorySnapshot update(UserContext actor, long id, long version, String content) {
        sql.actor(actor, true);
        if (mapper.updateProfileMemoriesWrite(new Object[]{content, id, actor.userId(), version}) != 1)
            throw LabException.denied();
        resetSessionContexts(actor);
        return new MemorySnapshot(id, actor.userId(), content, version + 1);
    }

    /**
     * 删除立即停止后续上下文使用，当前尚未启用记忆缓存。
     */
    @Transactional
    public void delete(UserContext actor, long id, long version) {
        sql.actor(actor, true);
        if (mapper.deleteProfileMemoriesWrite(new Object[]{id, actor.userId(), version}) != 1)
            throw LabException.denied();
        resetSessionContexts(actor);
    }

    /** 偏好变化可能影响所有旧答案，同事务重置本人窗口、摘要与执行权，删除后不再复用派生内容。 */
    private void resetSessionContexts(UserContext actor) {
        mapper.resetSessionContextsSessionsWrite(new Object[]{actor.userId()});
    }

    /**
     * 记录模型实际尝试，不持久化原问题／正文／密钥。
     */
    public void record(TraceSnapshot t) {
        // 运行控制行先落库且保持不完整，崩溃或异步批次丢失不会误报完整。
        mapper.recordAiRunsWrite(new Object[]{t.traceId(), t.actorUserId(), t.status(), t.modelId(), t.attempts(), t.mock(), Timestamp.from(t.createdAt()), t.endedAt()==null?null:Timestamp.from(t.endedAt()), t.sessionId(), t.taskId(), t.ingestionId(), t.previousTraceId(), t.incomplete(), t.telemetryDropped(), t.nodeCount()});
    }

    /** 单运行至多2000节点，原子提交终态与图；观测事务不包含远程调用或预算消费。 */
    @Transactional
    public void recordGraph(TraceSnapshot run,List<TraceNode> nodes) {
        if(nodes.size()>2000 || nodes.size()!=run.nodeCount()) throw LabException.invalid("观测节点数量不匹配");
        record(run);
        var rows=new ArrayList<Object[]>();
        for(var node:nodes) {
            try {
                String json=traceJson.writeValueAsString(node);
                if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>2048) throw LabException.invalid("观测节点超限");
                rows.add(new Object[]{run.traceId(),node.spanId(),node.parentSpanId(),node.sequence(),node.type(),node.status(),Timestamp.from(node.startedAt()),node.endedAt()==null?null:Timestamp.from(node.endedAt()),json});
            } catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("观测编码失败"); }
        }
        // 批量发送有界排错记录，减少SQL往返；终态与节点仍在同一短事务。
        if (!rows.isEmpty()) mapper.recordGraphAiSpansWrite(rows);
    }

    /** 先核验运行所有者再取节点；管理员私人资源没有旁路。 */
    public TraceGraph graph(UserContext actor,String id) {
        var run=read(actor,id);
        var nodes=sql.project(mapper.graphAiSpansSelect(new Object[]{id, actor.userId()}), (r,n)->{
            try { return traceJson.readValue(r.string(1),TraceNode.class); }
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("观测读取失败"); }
        });
        var graph=TraceGraph.from(run,nodes);
        // 终态声明的数量与实际存储也核对，不用父子树猜测执行完整性。
        return nodes.size()==run.nodeCount()?graph:new TraceGraph(run,graph.nodes(),graph.edges(),graph.missingNodeIds(),true,"UNKNOWN");
    }

    /** 服务端恢复按同一逻辑任务查前驱；不查询其他用户内容或可靠状态。 */
    public String previous(long actor,Long task,Long ingestion) {
        if(task==null && ingestion==null) return null;
        return sql.project(mapper.previousAiRunsSelect(new Object[]{actor, task!=null?task:ingestion}, task != null), (r,n)->r.string(1)).stream().findFirst().orElse(null);
    }

    /** 每批最多100旧运行，孤立RUNNING超期也清理；级联只删除排错节点。 */
    @Transactional
    public int purge(java.time.Instant before,int maximum) {
        if(maximum<1 || maximum>100) throw LabException.invalid("清理批次超限");
        return mapper.purgeAiRunsWrite(new Object[]{Timestamp.from(before), maximum});
    }

    /** 服务器交付不允许更新他人的运行，重复标记保留首次时间。 */
    public void delivered(UserContext actor,String id,java.time.Instant time) {
        sql.actor(actor,false);
        mapper.deliveredAiRunsWrite(new Object[]{Timestamp.from(time), id, actor.userId()});
    }

    /**
     * 本人分页运行列表。
     */
    public List<TraceSnapshot> list(UserContext actor, int offset, int limit) {
        sql.actor(actor, false);
        return sql.project(mapper.listAiRunsSelect(new Object[]{actor.userId(), limit, offset}), this::trace);
    }

    /**
     * 本人运行详情，不相信知道 traceId 就有权限。
     */
    public TraceSnapshot read(UserContext actor, String id) {
        sql.actor(actor, false);
        return sql.project(mapper.readAiRunsSelect(new Object[]{actor.userId(), id}), this::trace).stream().findFirst().orElseThrow(LabException::denied);
    }

    /**
     * 偏好行转契约。
     */
    private MemorySnapshot memory(SqlRow r, int n) {
        return new MemorySnapshot(r.longValue("id"), r.longValue("user_id"), r.string("content"), r.longValue("version"));
    }

    /**
     * 脱敏运行行转契约。
     */
    private TraceSnapshot trace(SqlRow r, int n) {
        return new TraceSnapshot(r.string("trace_id"), r.longValue("actor_user_id"), r.string("status"), r.string("model_id"), r.intValue("attempts"), r.booleanValue("mock"), r.timestamp("created_at").toInstant(),r.timestamp("ended_at")==null?null:r.timestamp("ended_at").toInstant(),(Long)r.value("session_id"),(Long)r.value("task_id"),(Long)r.value("ingestion_id"),r.string("previous_trace_id"),r.booleanValue("incomplete"),r.booleanValue("telemetry_dropped"),r.intValue("node_count"),r.timestamp("first_deliverable_at")==null?null:r.timestamp("first_deliverable_at").toInstant());
    }
}
