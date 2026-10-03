package com.example.ailab.data.repository;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.util.*;
/** 记忆与运行均固定 actor 过滤，不引入管理员全局旁路。 */
@Repository
public class PrivateResourceRepository implements MemoryStorePort,TraceRecordPort {
    private final SqlSupport sql;
    /** 装配内部数据访问组件。 */
    public PrivateResourceRepository(SqlSupport sql){this.sql=sql;}
    /** 返回本人尚未删除的偏好。 */
    public List<MemorySnapshot> list(UserContext actor){sql.actor(actor,false);return sql.jdbc.query("SELECT * FROM profile_memories WHERE user_id=? AND deleted=FALSE ORDER BY id",this::memory,actor.userId());}
    /** 用户明确命令保存偏好，限定每人 100 条。 */
    @Transactional
    public MemorySnapshot create(UserContext actor,String content){sql.actor(actor,true);if(sql.jdbc.queryForObject("SELECT COUNT(*) FROM profile_memories WHERE user_id=? AND deleted=FALSE",Long.class,actor.userId())>=100)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","最多保存 100 条偏好");long id=sql.insert("INSERT INTO profile_memories(user_id,content) VALUES(?,?)",actor.userId(),content);return new MemorySnapshot(id,actor.userId(),content,1);}
    /** 本人版本 CAS 更正。 */
    @Transactional
    public MemorySnapshot update(UserContext actor,long id,long version,String content){sql.actor(actor,true);if(sql.jdbc.update("UPDATE profile_memories SET content=?,version=version+1 WHERE id=? AND user_id=? AND version=? AND deleted=FALSE",content,id,actor.userId(),version)!=1)throw LabException.denied();return new MemorySnapshot(id,actor.userId(),content,version+1);}
    /** 删除立即停止后续上下文使用，当前尚未启用记忆缓存。 */
    @Transactional
    public void delete(UserContext actor,long id,long version){sql.actor(actor,true);if(sql.jdbc.update("UPDATE profile_memories SET deleted=TRUE,version=version+1 WHERE id=? AND user_id=? AND version=? AND deleted=FALSE",id,actor.userId(),version)!=1)throw LabException.denied();}
    /** 记录模型实际尝试，不持久化原问题／正文／密钥。 */
    public void record(TraceSnapshot t){sql.jdbc.update("INSERT INTO ai_runs(trace_id,actor_user_id,status,model_id,attempts,mock,created_at) VALUES(?,?,?,?,?,?,?)",t.traceId(),t.actorUserId(),t.status(),t.modelId(),t.attempts(),t.mock(),Timestamp.from(t.createdAt()));}
    /** 本人分页运行列表。 */
    public List<TraceSnapshot> list(UserContext actor,int offset,int limit){sql.actor(actor,false);return sql.jdbc.query("SELECT * FROM ai_runs WHERE actor_user_id=? ORDER BY created_at DESC LIMIT ? OFFSET ?",this::trace,actor.userId(),limit,offset);}
    /** 本人运行详情，不相信知道 traceId 就有权限。 */
    public TraceSnapshot read(UserContext actor,String id){sql.actor(actor,false);return sql.jdbc.query("SELECT * FROM ai_runs WHERE actor_user_id=? AND trace_id=?",this::trace,actor.userId(),id).stream().findFirst().orElseThrow(LabException::denied);}
    /** 偏好行转契约。 */
    private MemorySnapshot memory(ResultSet r,int n)throws SQLException{return new MemorySnapshot(r.getLong("id"),r.getLong("user_id"),r.getString("content"),r.getLong("version"));}
    /** 脱敏运行行转契约。 */
    private TraceSnapshot trace(ResultSet r,int n)throws SQLException{return new TraceSnapshot(r.getString("trace_id"),r.getLong("actor_user_id"),r.getString("status"),r.getString("model_id"),r.getInt("attempts"),r.getBoolean("mock"),r.getTimestamp("created_at").toInstant());}
}
