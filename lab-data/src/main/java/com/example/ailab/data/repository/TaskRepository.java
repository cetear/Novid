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
/** 短事务任务状态机，外部模型与报告生成均在事务外。 */
@Repository
public class TaskRepository implements TaskStorePort,ArtifactStorePort {
    private final SqlSupport sql;private final DocumentSqlRepository docs;private final ObjectMapper json=new ObjectMapper();
    /** 使用同一权威数据库与来源规则。 */
    public TaskRepository(SqlSupport sql,DocumentSqlRepository docs){this.sql=sql;this.docs=docs;}
    /** 创建／参数 hash／用户命名空间去重在一个事务内。 */
    @Transactional
    public TaskSnapshot create(UserContext actor,TaskRequest request){
        sql.actor(actor,true);String serialized=encode(new TaskRequest(request.taskType(),request.topic(),request.scope(),request.documentIds().stream().distinct().sorted().toList(),request.idempotencyKey())),hash=SqlSupport.hash(serialized);
        var old=sql.jdbc.query("SELECT request_hash,resource_id FROM request_deduplications WHERE actor_user_id=? AND namespace='TASK_CREATE' AND request_key=?",(r,n)->Map.entry(r.getString(1),r.getLong(2)),actor.userId(),request.idempotencyKey());
        if(!old.isEmpty()){if(!old.get(0).getKey().equals(hash))throw new LabException("OPERATION_CONFLICT","相同任务键参数不同");return read(actor,old.get(0).getValue());}
        if(sql.jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks WHERE requester_user_id=? AND status NOT IN ('SUCCEEDED','PARTIAL','FAILED','CANCELLED')",Long.class,actor.userId())>=20)throw new LabException("RATE_LIMITED","未完成任务数量超过限额");
        long id=sql.insert("INSERT INTO ai_tasks(requester_user_id,task_type,request_json,request_hash) VALUES(?,?,?,?)",actor.userId(),request.taskType(),serialized,hash);
        sql.jdbc.update("INSERT INTO request_deduplications(actor_user_id,namespace,request_key,request_hash,resource_id,expires_at) VALUES(?,'TASK_CREATE',?,?,?,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 7 DAY))",actor.userId(),request.idempotencyKey(),hash,id);return read(actor,id);
    }
    /** 任何角色都必须是请求者。 */
    public TaskSnapshot read(UserContext actor,long id){sql.actor(actor,false);return sql.jdbc.query("SELECT * FROM ai_tasks WHERE id=? AND requester_user_id=?",this::task,id,actor.userId()).stream().findFirst().orElseThrow(LabException::denied);}
    /** pause/cancel 立即 fencing，当前模型可继续计费但不能提交检查点／产物。 */
    @Transactional
    public TaskSnapshot action(UserContext actor,long id,String action){
        sql.actor(actor,true);var t=read(actor,id);String state=switch(action){
            case "pause"->{if(!Set.of("QUEUED","RUNNING").contains(t.status()))throw new LabException("OPERATION_CONFLICT","当前状态不能暂停");yield "PAUSED";}
            case "resume"->{if(!t.status().equals("PAUSED"))throw new LabException("OPERATION_CONFLICT","仅暂停任务可以恢复");yield "QUEUED";}
            case "cancel"->{if(Set.of("SUCCEEDED","PARTIAL","FAILED","CANCELLED").contains(t.status()))throw new LabException("OPERATION_CONFLICT","终态不能取消");yield "CANCELLED";}
            default->throw LabException.invalid("只支持 pause/resume/cancel");
        };
        sql.jdbc.update("UPDATE ai_tasks SET status=?,state_version=state_version+1,fencing_token=fencing_token+1,total_execution_seconds=total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))),claimed_at=NULL,lease_until=NULL,worker_id=NULL WHERE id=?",state,id);return read(actor,id);
    }
    /** 程序领取当前角色；attempt 仅统计异常租约恢复，模型和累计时长预算保持单调。 */
    @Transactional
    public Optional<TaskLease> claim(String worker){
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE",Integer.class);
        sql.jdbc.update("UPDATE ai_tasks t JOIN users u ON u.id=t.requester_user_id SET t.status=\'CANCELLED\',t.state_version=t.state_version+1,t.fencing_token=t.fencing_token+1,t.worker_id=NULL,t.lease_until=NULL WHERE u.enabled=FALSE AND t.status IN (\'QUEUED\',\'RUNNING\',\'PAUSED\')");
        // 正常暂停不消耗异常租约恢复次数；超限队列明确终止，不能永久停留 QUEUED。
        sql.jdbc.update("UPDATE ai_tasks SET status='FAILED',error_code='BUDGET_EXCEEDED',fencing_token=fencing_token+1,state_version=state_version+1,worker_id=NULL,lease_until=NULL WHERE status IN ('QUEUED','RUNNING') AND (total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)))>=1200 OR status='RUNNING' AND attempt>=3 AND lease_until<CURRENT_TIMESTAMP(6) OR (model_attempts>=10 OR model_turns>=6) AND completed_steps<3 AND (status='QUEUED' OR lease_until<CURRENT_TIMESTAMP(6)))");
        var ids=sql.jdbc.query("SELECT t.id FROM ai_tasks t JOIN users u ON u.id=t.requester_user_id WHERE u.enabled=TRUE AND u.password_change_required=FALSE AND (t.status='QUEUED' OR t.status='RUNNING' AND t.attempt<3 AND t.lease_until<CURRENT_TIMESTAMP(6)) ORDER BY t.id LIMIT 1 FOR UPDATE SKIP LOCKED",(r,n)->r.getLong(1));
        if(ids.isEmpty())return Optional.empty();long id=ids.get(0);
        sql.jdbc.update("UPDATE ai_tasks SET attempt=attempt+IF(status='RUNNING',1,0),status='RUNNING',state_version=state_version+1,fencing_token=fencing_token+1,worker_id=?,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),total_execution_seconds=total_execution_seconds+IF(claimed_at IS NULL,0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))),claimed_at=CURRENT_TIMESTAMP(6) WHERE id=?",worker,id);
        return sql.jdbc.query("SELECT t.*,u.role,u.permission_version,u.enabled,u.password_change_required FROM ai_tasks t JOIN users u ON u.id=t.requester_user_id WHERE t.id=?",(r,n)->new TaskLease(task(r,n),decode(r.getString("request_json"),TaskRequest.class),new UserContext(r.getLong("requester_user_id"),UserContext.Role.valueOf(r.getString("role")),r.getBoolean("enabled"),r.getLong("permission_version"),r.getBoolean("password_change_required")),worker,r.getLong("fencing_token")),id).stream().findFirst();
    }
    /** 续租失败后执行者不能开始新的模型／工具步骤。 */
    @Transactional
    public boolean renew(TaskLease lease){try{valid(lease);sql.jdbc.update("UPDATE ai_tasks SET lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",lease.task().taskId());return true;}catch(LabException e){return false;}}
    /** 持久调用计数独立于可丢遥测，重启不恢复十次额度。 */
    @Transactional
    public void reserveModelAttempt(TaskLease lease){valid(lease);if(sql.jdbc.update("UPDATE ai_tasks SET model_attempts=model_attempts+1 WHERE id=? AND model_attempts<10",lease.task().taskId())!=1)throw new LabException("BUDGET_EXCEEDED","持久模型尝试预算耗尽");}
    /** 逻辑生成轮数与真实尝试分开计量，全部角色共享上限。 */
    @Transactional
    public void reserveModelTurn(TaskLease lease){valid(lease);if(sql.jdbc.update("UPDATE ai_tasks SET model_turns=model_turns+1 WHERE id=? AND model_turns<6",lease.task().taskId())!=1)throw new LabException("BUDGET_EXCEEDED","持久模型轮数耗尽");}
    /** 完成检查点唯一键确保恢复跳过已成功步骤。 */
    @Transactional
    public void checkpoint(TaskLease lease,TaskCheckpoint checkpoint){
        valid(lease);docs.verifySources(all(lease.actor()),checkpoint.sourceDependencies());
        int count=sql.jdbc.queryForObject("SELECT COUNT(*) FROM task_steps WHERE task_id=? AND step_id=?",Integer.class,lease.task().taskId(),checkpoint.stepId());if(count>0)return;
        sql.jdbc.update("INSERT INTO task_steps(task_id,step_id,content,source_json,partial) VALUES(?,?,?,?,?)",lease.task().taskId(),checkpoint.stepId(),checkpoint.content(),encode(checkpoint.sourceDependencies()),checkpoint.partial());
        sql.jdbc.update("UPDATE ai_tasks SET completed_steps=completed_steps+1 WHERE id=?",lease.task().taskId());
    }
    /** 恢复读取成功事实，来源已撤销则停止。 */
    public List<TaskCheckpoint> checkpoints(TaskLease lease){sql.actor(lease.actor(),false);var checkpoints=sql.jdbc.query("SELECT * FROM task_steps WHERE task_id=? ORDER BY step_id",(r,n)->new TaskCheckpoint(r.getString("step_id"),r.getString("content"),sources(r.getString("source_json")),r.getBoolean("partial")),lease.task().taskId());for(var c:checkpoints)docs.verifySources(all(lease.actor()),c.sourceDependencies());return checkpoints;}
    /** 产物发布与任务终态同事务，取消先提交时无法发布。 */
    @Transactional
    public void publish(TaskLease lease,TaskCheckpoint report){
        valid(lease);docs.verifySources(all(lease.actor()),report.sourceDependencies());
        if(report.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw new LabException("BUDGET_EXCEEDED","报告超过 1 MB");
        long id=sql.insert("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum) VALUES(?,?,'report.md','text/markdown',?,?,?)",lease.actor().userId(),lease.task().taskId(),report.content(),encode(report.sourceDependencies()),SqlSupport.hash(report.content()));
        sql.jdbc.update("UPDATE ai_tasks SET status=?,artifact_id=?,state_version=state_version+1,lease_until=NULL,worker_id=NULL,total_execution_seconds=total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)),claimed_at=NULL WHERE id=?",report.partial()?"PARTIAL":"SUCCEEDED",id,lease.task().taskId());
    }
    /** 旧执行者不覆盖新状态；真实失败明确终止，不无限自动重试模型。 */
    @Transactional
    public void fail(TaskLease lease,String code){try{valid(lease);}catch(LabException e){return;}sql.jdbc.update("UPDATE ai_tasks SET status='FAILED',error_code=?,state_version=state_version+1,lease_until=NULL,worker_id=NULL,total_execution_seconds=total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)),claimed_at=NULL WHERE id=?",code,lease.task().taskId());}
    /** 私人下载同时要求任务发布态和当前全部来源仍可读。 */
    public ArtifactSnapshot artifact(UserContext actor,long id){
        sql.actor(actor,false);var result=sql.jdbc.query("SELECT a.* FROM artifacts a JOIN ai_tasks t ON t.id=a.task_id WHERE a.id=? AND a.requester_user_id=? AND a.published=TRUE AND t.status IN ('SUCCEEDED','PARTIAL')",(r,n)->new ArtifactSnapshot(r.getLong("id"),actor.userId(),r.getLong("task_id"),r.getString("filename"),r.getString("mime"),r.getString("content"),sources(r.getString("source_json"))),id,actor.userId()).stream().findFirst().orElseThrow(LabException::denied);docs.verifySources(all(actor),result.sourceDependencies());return result;
    }
    /** fencing/worker/租约/用户/工作流/累计期限全都必须满足。 */
    private void valid(TaskLease lease){
        sql.actor(lease.actor(),true);
        int count=sql.jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks WHERE id=? AND requester_user_id=? AND worker_id=? AND fencing_token=? AND status='RUNNING' AND lease_until>CURRENT_TIMESTAMP(6) AND workflow_version='report-v1' AND total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6))<1200",Integer.class,lease.task().taskId(),lease.actor().userId(),lease.workerId(),lease.fencingToken());
        if(count!=1)throw new LabException("STALE_EXECUTION","任务执行权或累计期限已失效");
    }
    /** 不返回内部 lease/worker/私有文件路径。 */
    private TaskSnapshot task(ResultSet r,int n)throws SQLException{return new TaskSnapshot(r.getLong("id"),r.getLong("requester_user_id"),r.getString("task_type"),r.getString("status"),r.getLong("state_version"),r.getInt("model_attempts"),r.getInt("completed_steps"),r.getString("error_code"),(Long)r.getObject("artifact_id"));}
    /** 原始来源复核按当前角色，不能因产物本人所有而绕过。 */
    private AuthorizedKnowledgeScope all(UserContext actor){return new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now());}
    /** 固定 DTO JSON 序列化，无 Java 反序列化。 */
    private String encode(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException(e);}}
    /** 仅使用明确的请求类型反序列化。 */
    private <T>T decode(String s,Class<T> type){try{return json.readValue(s,type);}catch(Exception e){throw new LabException("INVALID_ARGUMENTS","任务请求数据损坏");}}
    /** 来源只解析固定列表类型。 */
    private List<SourceDependency> sources(String s){try{return json.readValue(s,new TypeReference<List<SourceDependency>>(){});}catch(Exception e){throw new LabException("CONTEXT_MAPPING_INVALID","来源数据损坏");}}
}
