package com.example.ailab.data.repository;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.support.*;
import org.springframework.stereotype.Component;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
/** data 内部 SQL 与授权条件辅助；不向其他模块暴露 SQL 或 Wrapper。 */
@Component
public class SqlSupport {
    public final JdbcTemplate jdbc;public final NamedParameterJdbcTemplate named;
    /** 使用同一 DataSource，事务不会覆盖远程调用。 */
    public SqlSupport(JdbcTemplate jdbc){this.jdbc=jdbc;named=new NamedParameterJdbcTemplate(jdbc);}
    /** user 行锁与权限版本核验，写入以数据库当前事实为准。 */
    public void actor(UserContext actor,boolean lock){
        if(lock)jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE",Integer.class);
        var rows=jdbc.query("SELECT * FROM users WHERE id=?"+(lock?" FOR UPDATE":""),SqlSupport::user,actor.userId());
        if(rows.isEmpty())throw LabException.denied();var u=rows.get(0);
        if(!u.enabled()||u.role()!=actor.role()||u.permissionVersion()!=actor.permissionVersion()||u.passwordChangeRequired())throw LabException.denied();
    }
    /** owner 写校验；使用 actor → base 的锁顺序。 */
    public KnowledgeBaseSnapshot owner(UserContext actor,long id,boolean enabled){
        actor(actor,true);var rows=jdbc.query("SELECT * FROM knowledge_bases WHERE id=? FOR UPDATE",SqlSupport::base,id);
        if(rows.isEmpty())throw LabException.denied();var k=rows.get(0);
        if(k.deleted()||k.ownerUserId()!=actor.userId()||enabled&&!k.enabled())throw LabException.denied();return k;
    }
    /** 构建启用／删除／本人／选择范围条件；空列表永远不是 ALL。 */
    public String scope(AuthorizedKnowledgeScope scope,MapSqlParameterSource p){
        actor(scope.actor(),false);p.addValue("actor",scope.actor().userId());
        if(scope.actor().role()!=UserContext.Role.ADMIN&&scope.mode()==ScopeRequest.Mode.ALL)throw LabException.denied();
        String filter="k.enabled=TRUE AND k.deleted=FALSE";
        if(scope.actor().role()!=UserContext.Role.ADMIN||scope.mode()==ScopeRequest.Mode.SELF)filter+=" AND k.owner_user_id=:actor";
        if(scope.mode()==ScopeRequest.Mode.SELECTED){if(scope.knowledgeBaseIds().isEmpty())filter+=" AND 1=0";else{p.addValue("ids",scope.knowledgeBaseIds());filter+=" AND k.id IN (:ids)";}}
        if(scope.ownerUserId()!=null){if(scope.actor().role()!=UserContext.Role.ADMIN&&scope.ownerUserId()!=scope.actor().userId())throw LabException.denied();p.addValue("owner",scope.ownerUserId());filter+=" AND k.owner_user_id=:owner";}
        return filter;
    }
    /** 获取自增主键，不依赖 LAST_INSERT_ID 的连接外调用。 */
    public long insert(String sql,Object...args){
        var key=new GeneratedKeyHolder();jdbc.update(connection->{var statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);for(int i=0;i<args.length;i++)statement.setObject(i+1,args[i]);return statement;},key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    /** 规范化哈希输入，写操作不接受客户端提供的哈希作为事实。 */
    public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    /** data 内部行映射，返回契约而不是 PO。 */
    public static UserSnapshot user(ResultSet r,int n)throws SQLException{return new UserSnapshot(r.getLong("id"),r.getString("username"),UserContext.Role.valueOf(r.getString("role")),r.getBoolean("enabled"),r.getLong("permission_version"),r.getBoolean("password_change_required"));}
    /** 知识库快照转换。 */
    public static KnowledgeBaseSnapshot base(ResultSet r,int n)throws SQLException{return new KnowledgeBaseSnapshot(r.getLong("id"),r.getLong("owner_user_id"),r.getString("name"),r.getString("description"),r.getBoolean("enabled"),r.getBoolean("deleted"),r.getLong("version"));}
    /** 原文元数据转换，不泄露路径或内部账号凭证。 */
    public static DocumentSnapshot document(ResultSet r,int n)throws SQLException{return new DocumentSnapshot(r.getLong("id"),r.getLong("knowledge_base_id"),r.getLong("owner_user_id"),r.getString("title"),r.getString("format"),r.getInt("current_version"),r.getString("ingestion_status"),(Long)r.getObject("active_processing_revision"));}
    /** Outbox 与权威业务记录处于同一事务。 */
    public void event(String type,long id,long version){jdbc.update("INSERT INTO outbox_events(event_type,resource_id,resource_version) VALUES(?,?,?)",type,id,version);}
    /** 资料更新使 ALL 缓存 epoch 单调增加，当前尚未启用答案缓存。 */
    public void changed(){jdbc.update("UPDATE system_control SET knowledge_epoch=knowledge_epoch+1 WHERE id=1");}
}
