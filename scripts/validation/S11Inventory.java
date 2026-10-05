package com.example.ailab.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.sql.*;
import java.security.MessageDigest;
import java.util.*;

/** S11只读保护快照：不启动Spring、迁移或Worker，不读取用户正文及密码／令牌字段。 */
public final class S11Inventory {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Map<String,String> QUERIES=Map.ofEntries(
        Map.entry("users","SELECT id,role,enabled,permission_version,password_change_required FROM users ORDER BY id"),
        Map.entry("knowledge_bases","SELECT id,owner_user_id,version,enabled,deleted FROM knowledge_bases ORDER BY id"),
        Map.entry("documents","SELECT id,owner_user_id,knowledge_base_id,current_version,deleted FROM documents ORDER BY id"),
        Map.entry("document_versions","SELECT document_id,document_version,checksum,ingestion_status,active_processing_revision FROM document_versions ORDER BY document_id,document_version"),
        Map.entry("ai_tasks","SELECT id,requester_user_id,status,state_version,fencing_token,model_attempts,model_turns,tool_calls,model_repairs,media_deadline FROM ai_tasks ORDER BY id"),
        Map.entry("sessions","SELECT id,user_id,version,deleted FROM sessions ORDER BY id"),
        Map.entry("media_operations","SELECT operation_id,task_id,state,provider_job_id,poll_count FROM media_operations ORDER BY operation_id"),
        Map.entry("artifacts","SELECT id,task_id,requester_user_id,checksum FROM artifacts ORDER BY id"),
        Map.entry("fee_attempts","SELECT operation_id,scope_id,state,reserved_amount,estimated_amount FROM fee_attempts ORDER BY operation_id"),
        Map.entry("flyway_schema_history","SELECT version,checksum,success FROM flyway_schema_history ORDER BY installed_rank")
    );

    /** 两次只读快照必须完全相同；发生正式服务并发变更时据实失败，不恢复覆盖用户变化。 */
    public static void main(String[] args) throws Exception {
        try {
            if(args.length!=1||!Set.of("before","after").contains(args[0]))throw new IllegalArgumentException("未知快照阶段");
            var env=LocalEnvironmentLoader.read(Path.of(".env"));
            var props=new Properties();props.setProperty("user",value(env,"DB_USERNAME"));props.setProperty("password",value(env,"DB_PASSWORD"));
            props.setProperty("connectTimeout","5000");props.setProperty("socketTimeout","15000");
            var snapshot=new TreeMap<String,Object>();
            try(var connection=DriverManager.getConnection(value(env,"DB_URL"),props)){
                connection.setReadOnly(true);connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setAutoCommit(false);
                snapshot.put("mysql_version",connection.getMetaData().getDatabaseProductVersion());
                for(var entry:QUERIES.entrySet())snapshot.put(entry.getKey(),digest(connection,entry.getValue()));
                connection.rollback();
            }
            Path root=Path.of("var/stage-S11");Files.createDirectories(root);
            Files.writeString(root.resolve("inventory-"+args[0]+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));
            if(args[0].equals("after")){
                var before=JSON.readTree(Files.readString(root.resolve("inventory-before.json")));
                // JSON反读将小整数归一为IntNode；两侧同样反读，不能把LongNode表示差异误报为资料变化。
                if(!before.equals(JSON.readTree(JSON.writeValueAsBytes(snapshot))))throw new AssertionError("原有元数据快照变化；需核对，禁止覆盖用户更改");
            }
            System.out.println("S11_INVENTORY_"+args[0].toUpperCase(Locale.ROOT)+"_PASS tables="+QUERIES.size());
        }catch(Throwable error){
            // 数据源异常的原始message可能含连接信息，只输出类型和SQLState。
            String reason=error instanceof SQLException sql?"SQLState="+sql.getSQLState()+" code="+sql.getErrorCode():error.getClass().getSimpleName();
            System.err.println("S11_INVENTORY_FAIL "+reason);System.exit(1);
        }
    }
    /** 使用与正式加载器相同的显式覆盖顺序；只在连接内消费，不打印配置值。 */
    private static String value(Map<String,Object> local,String key){
        String value=System.getProperty(key);if(value==null)value=System.getenv(key);
        if(value==null)value=Objects.toString(local.get(key),"");return value;
    }
    /** 逐行摘要只保留计数与哈希，提供方ID也不出现在证据内容中。 */
    private static Map<String,Object> digest(Connection connection,String query)throws Exception{
        var hash=MessageDigest.getInstance("SHA-256");long count=0;
        try(var statement=connection.createStatement();var rows=statement.executeQuery(query)){
            int columns=rows.getMetaData().getColumnCount();
            while(rows.next()){
                var row=new ArrayList<String>();for(int i=1;i<=columns;i++)row.add(rows.getString(i));
                hash.update(JSON.writeValueAsBytes(row));hash.update((byte)'\n');count++;
            }
        }
        return Map.of("count",count,"sha256",HexFormat.of().formatHex(hash.digest()));
    }
}
