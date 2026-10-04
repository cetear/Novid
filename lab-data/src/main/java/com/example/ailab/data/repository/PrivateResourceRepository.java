package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.util.*;

/**
 * 记忆与运行均固定 actor 过滤，不引入管理员全局旁路。
 */
@Repository
public class PrivateResourceRepository implements MemoryStorePort, TraceRecordPort {
    private final SqlSupport sql;
    private final com.fasterxml.jackson.databind.ObjectMapper traceJson = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();

    /**
     * 装配内部数据访问组件。
     */
    public PrivateResourceRepository(SqlSupport sql) {
        this.sql = sql;
    }

    /**
     * 返回本人尚未删除的偏好。
     */
    public List<MemorySnapshot> list(UserContext actor) {
        sql.actor(actor, false);
        return sql.jdbc.query("SELECT * FROM profile_memories WHERE user_id=? AND deleted=FALSE ORDER BY id", this::memory, actor.userId());
    }

    /**
     * 用户明确命令保存偏好，限定每人 100 条。
     */
    @Transactional
    public MemorySnapshot create(UserContext actor, String content) {
        sql.actor(actor, true);
        if (sql.jdbc.queryForObject("SELECT COUNT(*) FROM profile_memories WHERE user_id=? AND deleted=FALSE", Long.class, actor.userId()) >= 100)
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "最多保存 100 条偏好");
        long id = sql.insert("INSERT INTO profile_memories(user_id,content) VALUES(?,?)", actor.userId(), content);
        resetSessionContexts(actor);
        return new MemorySnapshot(id, actor.userId(), content, 1);
    }

    /**
     * 本人版本 CAS 更正。
     */
    @Transactional
    public MemorySnapshot update(UserContext actor, long id, long version, String content) {
        sql.actor(actor, true);
        if (sql.jdbc.update("UPDATE profile_memories SET content=?,version=version+1 WHERE id=? AND user_id=? AND version=? AND deleted=FALSE", content, id, actor.userId(), version) != 1)
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
        if (sql.jdbc.update("UPDATE profile_memories SET deleted=TRUE,version=version+1 WHERE id=? AND user_id=? AND version=? AND deleted=FALSE", id, actor.userId(), version) != 1)
            throw LabException.denied();
        resetSessionContexts(actor);
    }

    /** 偏好变化可能影响所有旧答案，同事务重置本人窗口、摘要与执行权，删除后不再复用派生内容。 */
    private void resetSessionContexts(UserContext actor) {
        sql.jdbc.update("UPDATE sessions SET context_floor_seq=next_seq-1,summary_content=NULL,summary_covered_through_seq=NULL,summary_source_json=NULL,version=version+1,execution_id=NULL,lease_until=NULL WHERE user_id=? AND deleted=FALSE", actor.userId());
    }

    /**
     * 记录模型实际尝试，不持久化原问题／正文／密钥。
     */
    public void record(TraceSnapshot t) {
        // 运行控制行先落库且保持不完整，崩溃或异步批次丢失不会误报完整。
        sql.jdbc.update("INSERT INTO ai_runs(trace_id,actor_user_id,status,model_id,attempts,mock,created_at,ended_at,session_id,task_id,ingestion_id,previous_trace_id,incomplete,telemetry_dropped,node_count) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status=VALUES(status),model_id=VALUES(model_id),attempts=VALUES(attempts),mock=VALUES(mock),ended_at=VALUES(ended_at),incomplete=VALUES(incomplete),telemetry_dropped=VALUES(telemetry_dropped),node_count=VALUES(node_count)",
                t.traceId(),t.actorUserId(),t.status(),t.modelId(),t.attempts(),t.mock(),Timestamp.from(t.createdAt()),t.endedAt()==null?null:Timestamp.from(t.endedAt()),t.sessionId(),t.taskId(),t.ingestionId(),t.previousTraceId(),t.incomplete(),t.telemetryDropped(),t.nodeCount());
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
        sql.jdbc.batchUpdate("INSERT INTO ai_spans(trace_id,span_id,parent_span_id,sequence_no,node_type,status,started_at,ended_at,node_json) VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE span_id=span_id",rows);
    }

    /** 先核验运行所有者再取节点；管理员私人资源没有旁路。 */
    public TraceGraph graph(UserContext actor,String id) {
        var run=read(actor,id);
        var nodes=sql.jdbc.query("SELECT s.node_json FROM ai_spans s JOIN ai_runs r ON r.trace_id=s.trace_id WHERE s.trace_id=? AND r.actor_user_id=? ORDER BY s.sequence_no LIMIT 2000",(r,n)->{
            try { return traceJson.readValue(r.getString(1),TraceNode.class); }
            catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("观测读取失败"); }
        },id,actor.userId());
        var graph=TraceGraph.from(run,nodes);
        // 终态声明的数量与实际存储也核对，不用父子树猜测执行完整性。
        return nodes.size()==run.nodeCount()?graph:new TraceGraph(run,graph.nodes(),graph.edges(),graph.missingNodeIds(),true,"UNKNOWN");
    }

    /** 服务端恢复按同一逻辑任务查前驱；不查询其他用户内容或可靠状态。 */
    public String previous(long actor,Long task,Long ingestion) {
        if(task==null && ingestion==null) return null;
        return sql.jdbc.query("SELECT trace_id FROM ai_runs WHERE actor_user_id=? AND " +(task!=null?"task_id=?":"ingestion_id=?")+" ORDER BY created_at DESC,trace_id DESC LIMIT 1",(r,n)->r.getString(1),actor,task!=null?task:ingestion).stream().findFirst().orElse(null);
    }

    /** 每批最多100旧运行，孤立RUNNING超期也清理；级联只删除排错节点。 */
    @Transactional
    public int purge(java.time.Instant before,int maximum) {
        if(maximum<1 || maximum>100) throw LabException.invalid("清理批次超限");
        return sql.jdbc.update("DELETE FROM ai_runs WHERE created_at<? ORDER BY created_at LIMIT ?",Timestamp.from(before),maximum);
    }

    /** 服务器交付不允许更新他人的运行，重复标记保留首次时间。 */
    public void delivered(UserContext actor,String id,java.time.Instant time) {
        sql.actor(actor,false);
        sql.jdbc.update("UPDATE ai_runs SET first_deliverable_at=COALESCE(first_deliverable_at,?) WHERE trace_id=? AND actor_user_id=?",Timestamp.from(time),id,actor.userId());
    }

    /**
     * 本人分页运行列表。
     */
    public List<TraceSnapshot> list(UserContext actor, int offset, int limit) {
        sql.actor(actor, false);
        return sql.jdbc.query("SELECT * FROM ai_runs WHERE actor_user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?", this::trace, actor.userId(), limit, offset);
    }

    /**
     * 本人运行详情，不相信知道 traceId 就有权限。
     */
    public TraceSnapshot read(UserContext actor, String id) {
        sql.actor(actor, false);
        return sql.jdbc.query("SELECT * FROM ai_runs WHERE actor_user_id=? AND trace_id=?", this::trace, actor.userId(), id).stream().findFirst().orElseThrow(LabException::denied);
    }

    /**
     * 偏好行转契约。
     */
    private MemorySnapshot memory(ResultSet r, int n) throws SQLException {
        return new MemorySnapshot(r.getLong("id"), r.getLong("user_id"), r.getString("content"), r.getLong("version"));
    }

    /**
     * 脱敏运行行转契约。
     */
    private TraceSnapshot trace(ResultSet r, int n) throws SQLException {
        return new TraceSnapshot(r.getString("trace_id"), r.getLong("actor_user_id"), r.getString("status"), r.getString("model_id"), r.getInt("attempts"), r.getBoolean("mock"), r.getTimestamp("created_at").toInstant(),r.getTimestamp("ended_at")==null?null:r.getTimestamp("ended_at").toInstant(),(Long)r.getObject("session_id"),(Long)r.getObject("task_id"),(Long)r.getObject("ingestion_id"),r.getString("previous_trace_id"),r.getBoolean("incomplete"),r.getBoolean("telemetry_dropped"),r.getInt("node_count"),r.getTimestamp("first_deliverable_at")==null?null:r.getTimestamp("first_deliverable_at").toInstant());
    }
}
