import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.app.LabApplication;
import com.example.ailab.business.application.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.*;
import com.example.ailab.ai.model.*;
import com.example.ailab.ai.rag.DocumentIngestionPipeline;
import com.example.ailab.ai.orchestration.planexecute.PlanValidator;
import com.example.ailab.ai.workflows.report.ReportTaskWorker;
import com.example.ailab.data.search.*;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import com.sun.net.httpserver.HttpServer;

/** 使用正式应用、真实服务和独立临时资源的验收探针；不修改配置库、原索引或生产源码。 */
public class BackendValidation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Map<String,Object>> RESULTS = new ArrayList<>();
    private static final Map<String,Object> META = new LinkedHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static ConfigurableApplicationContext context;
    private static AccountApplicationService accounts;
    private static KnowledgeBaseApplicationService bases;
    private static DocumentApplicationService documents;
    private static PersonalApplicationService personal;
    private static TaskApplicationService taskApplication;
    private static TaskStorePort taskStore;
    private static DocumentIngestionStorePort ingestion;
    private static JdbcTemplate jdbc;
    private static UserContext admin, user1, user2;
    private static String adminToken, token1, token2, origin;
    private static KnowledgeBaseSnapshot base1, base2, adminBase;
    private static DocumentSnapshot document1;
    private static RestClientTransport esTransport;
    private static ElasticsearchClient es;
    private static String testIndex, testSchema, configuredUrl, testUrl;
    private static boolean schemaCreated, indexCreated;
    private static boolean offline;
    private static boolean focused;
    private static String userPrefix;
    private static final String PASSWORD = "Review-"+UUID.randomUUID()+"-2026";
    private static final String FIXTURE = "# 验收资料\n\n项目代号为晨星。\n验收编号为 NVD-7319。\n负责人是测试用户。\n\n## 发布规则\n\n每周三做一次备份；保留七份备份。\n\n```text\n# 代码中的井号不是章节\n```\n";
    /** 资源不足和程序失败分列，且完整验收均不能退出为通过。 */
    private static final class IsolationUnavailable extends RuntimeException { }
    @FunctionalInterface interface Checked { void run() throws Exception; }

    private static void check(String name, Checked action) {
        long started = System.nanoTime();
        var result = new LinkedHashMap<String,Object>();
        result.put("case",name);
        try { action.run(); result.put("status","PASS"); }
        catch (Throwable e) { result.put("status","FAIL"); result.put("reason",safeReason(e)); }
        result.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);
        RESULTS.add(result);
        System.out.println(toJson(result));
    }
    private static String safeReason(Throwable e) {
        if(e instanceof AssertionError) return String.valueOf(e.getMessage());
        for(Throwable current=e;current!=null;current=current.getCause()) {
            if(current instanceof org.flywaydb.core.internal.sqlscript.FlywaySqlScriptException script) {
                META.put("migration_error_file",script.getResource().getFilename());META.put("migration_error_line",script.getLineNumber());META.put("migration_error_statement",script.getStatement());
            }
            if(current instanceof LabException lab) return lab.code();
            if(current instanceof SQLException sql) return sql.getClass().getSimpleName()+"/SQLState="+sql.getSQLState()+"/code="+sql.getErrorCode();
        }
        return e.getClass().getSimpleName();
    }
    private static String toJson(Object value) { try{return JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    private static void require(boolean ok,String safeMessage) { if(!ok)throw new AssertionError(safeMessage); }
    private static void denied(String code, Checked action) throws Exception {
        try { action.run(); } catch(LabException e) { require(code.equals(e.code()),"expected="+code+", actual="+e.code());return; }
        throw new AssertionError("expected="+code+", actual=SUCCESS");
    }
    private static HttpResponse<String> request(String method,String path,String token,Object body,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+"/api/v1"+path)).timeout(Duration.ofSeconds(75));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(body!=null)builder.header("Content-Type","application/json; charset=UTF-8");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(toJson(body),StandardCharsets.UTF_8));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static JsonNode expect(int status,HttpResponse<String> response) throws Exception {
        require(response.statusCode()==status,"expected HTTP "+status+", actual HTTP "+response.statusCode()+", code="+errorCode(response));
        return response.body().isBlank()?JSON.nullNode():JSON.readTree(response.body());
    }
    private static String errorCode(HttpResponse<String> response) {
        try{return JSON.readTree(response.body()).path("code").asText("none");}catch(Exception e){return "non-JSON";}
    }
    private static Connection configuredConnection() throws SQLException {
        var properties=new Properties();properties.setProperty("user",System.getenv("DB_USERNAME"));properties.setProperty("password",System.getenv("DB_PASSWORD"));
        properties.setProperty("connectTimeout","5000");properties.setProperty("socketTimeout","15000");
        return DriverManager.getConnection(configuredUrl,properties);
    }

    /** 随机独立库才启动应用；零购买模式关闭全部扫描和媒体外发。 */
    public static void main(String[] args) throws Exception {
        configuredUrl=System.getenv("DB_URL");
        if(args.length>0&&args[0].equals("--inspect-database")){inspectDatabase();return;}
        focused=args.length>0&&args[0].equals("--focused");
        offline=args.length>0&&args[0].equals("--offline");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        testSchema="novid_review_"+suffix;
        testIndex="novid_review_"+suffix;
        userPrefix="review_"+suffix+"_";
        META.put("date",java.time.OffsetDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).toString());META.put("models",offline?"SIMULATED":"REAL");META.put("isolation","random temporary MySQL schema and ES index");
        try {
            if(!focused)testLocalModelProtocol();
            try(var connection=configuredConnection()) {
                META.put("mysql_version",connection.getMetaData().getDatabaseProductVersion());
                try(var statement=connection.createStatement()) {
                    try { statement.executeUpdate("CREATE DATABASE `"+testSchema+"` CHARACTER SET utf8mb4");schemaCreated=true; }
                    catch(SQLException denied) {
                        // S11：建库权限不足时不回退配置库，更不对现有资料做空库清理。
                        if(denied.getErrorCode()!=1044 && denied.getErrorCode()!=1045)throw denied;
                        RESULTS.add(Map.of("case","isolated_database_required","status","NOT_RUN","reason","CREATE_DATABASE_DENIED"));
                        throw new IsolationUnavailable();
                    }
                }
            }
            var uri=URI.create(configuredUrl.substring("jdbc:".length()));
            testUrl="jdbc:mysql://"+uri.getRawAuthority()+"/"+testSchema+(uri.getRawQuery()==null?"":"?"+uri.getRawQuery());
            var application=new SpringApplication(LabApplication.class);
            context=application.run("--spring.datasource.url="+testUrl,"--server.port=0","--server.address=127.0.0.1",
                    "--lab.bootstrap.enabled=false","--lab.search.enabled=true","--lab.search.index="+testIndex,
                    "--lab.ingestion.worker-enabled=false","--lab.task.worker-enabled=false",
                    "--lab.media.worker-enabled=false","--lab.media.enabled=false","--lab.governance.cleanup-enabled=false",
                    "--lab.observability.export-enabled=false", "--lab.model.mode="+(offline?"mock":"real"),
                    "--lab.model.models.primary.output-limit=512","--logging.level.root=OFF","--spring.main.banner-mode=off");
            origin="http://127.0.0.1:"+((ServletWebServerApplicationContext)context).getWebServer().getPort();
            accounts=context.getBean(AccountApplicationService.class);bases=context.getBean(KnowledgeBaseApplicationService.class);
            documents=context.getBean(DocumentApplicationService.class);personal=context.getBean(PersonalApplicationService.class);
            taskApplication=context.getBean(TaskApplicationService.class);taskStore=context.getBean(TaskStorePort.class);
            ingestion=context.getBean(DocumentIngestionStorePort.class);jdbc=context.getBean(JdbcTemplate.class);
            check("real_mysql_all_current_migrations_and_spring_http_startup",()->{
                // 按正式包里的全部版本核对；追加迁移不再要求手工改一个过时的总数。
                var flyway=context.getBean(org.flywaydb.core.Flyway.class);flyway.validate();
                var info=flyway.info();require(info.pending().length==0,"unapplied migrations");
                require(Arrays.stream(info.all()).allMatch(i->i.getState().isApplied()),"migration set incomplete");
                META.put("migration_versions",Arrays.stream(info.applied()).map(i->i.getVersion().toString()).toList());
            });
            var config=context.getBean(SearchProperties.class);
            esTransport=new RestClientTransport(ElasticsearchClientFactory.create(config),new JacksonJsonpMapper());
            es=new ElasticsearchClient(esTransport);META.put("es_version",es.info().version().number());
            require(!es.indices().exists(r->r.index(testIndex)).value(),"random review index already exists");
            context.getBean(KnowledgeIndexPort.class).initialize();indexCreated=true;
            check("real_es_index_2048_dimensions",()->require(es.indices().getMapping(r->r.index(testIndex)).result().get(testIndex).mappings().properties().get("vector").denseVector().dims()==2048,"unexpected vector mapping"));
            setupAccounts();
            if(offline) {
                testAuthentication();testKnowledge();testFixedDataset();testNotesAndMemory();testTaskState();
                testSseDiagnostics();
                RESULTS.add(Map.of("case","real_model_quality_and_media","status","NOT_RUN","reason","ZERO_NEW_PURCHASE_SCOPE"));
            } else if(focused) {
                document1=documents.upload(user1,base1.id(),"review.md","text/markdown",FIXTURE.getBytes(StandardCharsets.UTF_8),"focused-upload");
                testSseDiagnostics();
                testResearchReport();
            } else {
                testAuthentication();testKnowledge();testNotesAndMemory();testRag();testTaskState();testRealReport();testDefects();
            }
        } catch(IsolationUnavailable unavailable) {
            META.put("isolation_available",false);
        } catch(Throwable e) {
            RESULTS.add(Map.of("case","validation_setup_or_execution","status","FAIL","reason",safeReason(e)));
            System.out.println("validation stopped: "+safeReason(e));
        } finally {
            if(context!=null)try{context.close();}catch(Exception e){META.put("context_cleanup",safeReason(e));}
            if(indexCreated&&es!=null)try{es.indices().delete(r->r.index(testIndex));META.put("temporary_es_index_removed",true);}catch(Exception e){META.put("temporary_es_index_removed",false);}
            if(esTransport!=null)try{esTransport.close();}catch(Exception ignored){}
            if(schemaCreated)try(var connection=configuredConnection();var statement=connection.createStatement()){
                // 只清理本进程成功创建的随机名称，绝不对配置中的数据库名执行 DROP。
                require(testSchema.matches("novid_review_[a-f0-9]{12}"),"unsafe cleanup schema name");
                require(!URI.create(configuredUrl.substring(5)).getPath().equals("/"+testSchema),"cleanup matches configured database");
                statement.executeUpdate("DROP DATABASE `"+testSchema+"`");META.put("temporary_mysql_schema_removed",true);
            }catch(Exception e){META.put("temporary_mysql_schema_removed",false);}
            long passed=RESULTS.stream().filter(r->r.get("status").equals("PASS")).count();
            META.put("passed",passed);META.put("failed",RESULTS.stream().filter(r->r.get("status").equals("FAIL")).count());
            META.put("not_run",RESULTS.stream().filter(r->r.get("status").equals("NOT_RUN")).count());
            Files.createDirectories(Path.of("var/backend-review"));
            Files.writeString(Path.of(focused?"var/backend-review/results-focused.json":"var/backend-review/results.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata",META,"cases",RESULTS)));
            System.out.println(toJson(META));
        }
        if(RESULTS.stream().anyMatch(r->r.get("status").equals("FAIL")))System.exit(1);
        if(RESULTS.stream().anyMatch(r->r.get("status").equals("NOT_RUN")))System.exit(2);
    }

    /** 本机协议故障注入不是提供方事故，不使用真实密钥。 */
    private static void testLocalModelProtocol() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var executor=Executors.newFixedThreadPool(2);server.setExecutor(executor);
        var backupCalls=new AtomicInteger();
        server.createContext("/primary/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();byte[] body="{\"error\":{\"message\":\"synthetic unavailable\",\"type\":\"server_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(503,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        for(String target:List.of("backup","truncated","slow"))server.createContext("/"+target+"/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();if(target.equals("backup"))backupCalls.incrementAndGet();
            if(target.equals("slow"))try{Thread.sleep(1500);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            String finish=target.equals("truncated")?"length":"stop";
            String response="{\"id\":\"review\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"review\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"synthetic answer\"},\"finish_reason\":\""+finish+"\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":1,\"total_tokens\":3}}";
            byte[] body=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
            check("local_http_protocol_503_uses_compatible_backup",()->{
                var model=protocolGateway(endpoint,List.of("primary","backup"),30);var budget=new ExecutionBudget(Duration.ofSeconds(10),3);
                require(model.chat("QA","synthetic","synthetic",budget).modelId().equals("backup")&&backupCalls.get()==1,"503 did not reach backup");
            });
            check("regression_local_http_protocol_truncated_model_output_rejected",()->{
                // 现行契约区分截断、拒绝和结构错误，不能沿用旧笼统错误码。
                denied("MODEL_TRUNCATED",()->protocolGateway(endpoint,List.of("truncated"),30).chat("QA","synthetic","synthetic",new ExecutionBudget(Duration.ofSeconds(10),2)));
            });
            check("regression_model_specific_timeout_is_enforced",()->{
                long start=System.nanoTime();try{protocolGateway(endpoint,List.of("slow"),1).chat("QA","synthetic","synthetic",new ExecutionBudget(Duration.ofSeconds(10),2));}catch(LabException e){require((System.nanoTime()-start)<Duration.ofMillis(1400).toNanos(),"configured one-second timeout exceeded");return;}
                throw new AssertionError("model timeoutSeconds=1 ignored; 1.5-second response accepted");
            });
        } finally {server.stop(0);executor.shutdownNow();}
        META.put("local_http_protocol_is_external_provider_validation",false);
    }
    /** 本机协议网关仅注入合成认证头，独立入口也不依赖外部脚本设置环境密钥。 */
    private static ModelGateway protocolGateway(String endpoint,List<String> names,int timeout) {
        var definitions=new LinkedHashMap<String,ModelProperties.Definition>();
        for(String name:names)definitions.put(name,new ModelProperties.Definition("openai-compatible",endpoint+"/"+name+"/v1",name,"REVIEW_LOCAL_MODEL_KEY",true,Set.of("CHAT"),Set.of("review"),Set.of("PRIVATE"),16000,64,0,timeout));
        var config=new ModelProperties("real",definitions,Map.of("qa",new ModelProperties.Profile(names,Set.of("CHAT"),Set.of("review"),true)),new ModelProperties.Routing("review",Map.of("QA","qa")),true);
        var environment=new org.springframework.core.env.StandardEnvironment();
        environment.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("synthetic-protocol",
                Map.of("REVIEW_LOCAL_MODEL_KEY","synthetic-local-only-key")));
        return new ModelGateway(new ModelRegistry(config,environment));
    }

    private static void inspectDatabase() throws Exception {
        var result=new LinkedHashMap<String,Object>();
        try(var connection=configuredConnection();var statement=connection.createStatement()) {
            result.put("mysql_version",connection.getMetaData().getDatabaseProductVersion());
            var tables=new ArrayList<String>();
            try(var rows=statement.executeQuery("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name")){while(rows.next())tables.add(rows.getString(1));}
            result.put("tables",tables);
            var rowCounts=new LinkedHashMap<String,Long>();
            for(String table:tables) {
                // 只读清理核验；表名来自元数据，使用转义后的标识符。
                try(var rows=statement.executeQuery("SELECT COUNT(*) FROM `"+table.replace("`","``")+"`")) {
                    rows.next();rowCounts.put(table,rows.getLong(1));
                }
            }
            result.put("table_row_counts",rowCounts);
            if(tables.contains("flyway_schema_history")){
                var migrations=new ArrayList<Map<String,Object>>();
                try(var rows=statement.executeQuery("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")){while(rows.next())migrations.add(Map.of("version",rows.getString(1),"success",rows.getBoolean(2)));}
                result.put("migrations",migrations);
            }
            if(tables.contains("users"))try(var rows=statement.executeQuery("SELECT COUNT(*) FROM users")){rows.next();result.put("user_count",rows.getInt(1));}
        }
        Files.writeString(Path.of("var/backend-review/database-state.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));System.out.println(toJson(result));
    }


    private static void setupAccounts() throws Exception {
        accounts.bootstrap(userPrefix+"admin",PASSWORD);
        adminToken=expect(200,request("POST","/auth/login",null,Map.of("username",userPrefix+"admin","password",PASSWORD),null)).path("token").asText();
        admin=accounts.authenticate(adminToken);
        var first=accounts.create(admin,userPrefix+"user1");var second=accounts.create(admin,userPrefix+"user2");
        String temporary=accounts.login(userPrefix+"user1",first.temporaryPassword()).token();
        check("temporary_password_blocks_business_http",()->expect(403,request("POST","/knowledge-bases",temporary,Map.of("name","blocked","description",""),null)));
        expect(200,request("POST","/auth/password",temporary,Map.of("oldPassword",first.temporaryPassword(),"newPassword",PASSWORD),null));
        check("password_change_revokes_token_http",()->expect(401,request("GET","/auth/me",temporary,null,null)));
        var temporary2=accounts.authenticate(accounts.login(userPrefix+"user2",second.temporaryPassword()).token());accounts.changePassword(temporary2,second.temporaryPassword(),PASSWORD);
        token1=accounts.login(userPrefix+"user1",PASSWORD).token();user1=accounts.authenticate(token1);
        token2=accounts.login(userPrefix+"user2",PASSWORD).token();user2=accounts.authenticate(token2);
        base1=bases.create(user1,"review user1","");base2=bases.create(user2,"review user2","");adminBase=bases.create(admin,"review admin","");
    }
    private static void testAuthentication() throws Exception {
        check("unauthenticated_http_rejected",()->expect(401,request("GET","/knowledge-bases",null,null,null)));
        check("url_token_http_rejected",()->expect(401,request("GET","/knowledge-bases?token=synthetic-invalid",null,null,null)));
        check("forged_owner_in_json_http_rejected",()->expect(400,request("POST","/knowledge-bases",token1,Map.of("name","bad","description","","ownerUserId",user2.userId()),null)));
        check("user_cannot_manage_users",()->expect(403,request("POST","/admin/users",token1,Map.of("username","forbidden"),null)));
        check("last_admin_protected",()->denied("OPERATION_CONFLICT",()->accounts.update(admin,admin.userId(),false,UserContext.Role.USER)));
        check("disabled_account_revokes_existing_http_token",()->{
            accounts.update(admin,user2.userId(),false,UserContext.Role.USER);expect(401,request("GET","/auth/me",token2,null,null));
            accounts.update(admin,user2.userId(),true,UserContext.Role.USER);token2=accounts.login(userPrefix+"user2",PASSWORD).token();user2=accounts.authenticate(token2);
        });
        check("logout_revokes_token",()->{String token=accounts.login(userPrefix+"user2",PASSWORD).token();expect(200,request("POST","/auth/logout",token,null,null));expect(401,request("GET","/auth/me",token,null,null));});
    }
    private static void testKnowledge() throws Exception {
        check("self_selected_all_scope_and_isolation_http",()->{
            require(expect(200,request("GET","/knowledge-bases",token1,null,null)).size()==1,"USER sees another knowledge base");
            require(expect(200,request("GET","/knowledge-bases?scopeMode=ALL",adminToken,null,null)).size()==3,"ADMIN ALL missing bases");
            expect(403,request("GET","/knowledge-bases?scopeMode=ALL",token1,null,null));
            expect(403,request("GET","/knowledge-bases?scopeMode=SELECTED&knowledgeBaseIds="+base1.id()+","+base2.id(),token1,null,null));
            require(bases.list(user1,new ScopeRequest(ScopeRequest.Mode.SELECTED,List.of(),null),0,20).isEmpty(),"empty SELECTED expanded scope");
        });
        check("admin_cross_base_read_but_no_write",()->{
            require(bases.read(admin,base1.id()).id()==base1.id(),"ADMIN cannot read source");
            denied("ACCESS_DENIED",()->bases.update(admin,base1.id(),base1.version(),"forbidden","",true));
        });
        check("knowledge_base_cas_conflict",()->{
            var changed=bases.update(user2,base2.id(),base2.version(),"review user2 updated","",true);
            denied("OPERATION_CONFLICT",()->bases.update(user2,base2.id(),base2.version(),"stale","",true));base2=changed;
        });
        check("upload_format_and_encoding_rejected",()->{
            denied("UNSUPPORTED_DOCUMENT_TYPE",()->documents.upload(user1,base1.id(),"bad.pdf","application/pdf",new byte[]{1},"bad-format"));
            denied("DOCUMENT_PARSE_FAILED",()->documents.upload(user1,base1.id(),"bad.txt","text/plain",new byte[]{(byte)0xff},"bad-utf8"));
        });
        check("real_http_multipart_upload_and_idempotency",()->{
            String boundary="ReviewBoundary7319";
            String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"knowledgeBaseId\"\r\n\r\n"+base1.id()+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"review.md\"\r\nContent-Type: text/markdown\r\n\r\n"+FIXTURE+"\r\n--"+boundary+"--\r\n";
            var http=HttpRequest.newBuilder(URI.create(origin+"/api/v1/documents")).header("Authorization","Bearer "+token1).header("Idempotency-Key","fixture-upload").header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofString(body,StandardCharsets.UTF_8)).build();
            JsonNode first=expect(202,HTTP.send(http,HttpResponse.BodyHandlers.ofString()));JsonNode second=expect(202,HTTP.send(http,HttpResponse.BodyHandlers.ofString()));
            require(first.path("id").asLong()==second.path("id").asLong(),"duplicate upload created another document");document1=documents.read(user1,first.path("id").asLong()).document();
            denied("OPERATION_CONFLICT",()->documents.upload(user1,base1.id(),"review.md","text/markdown","changed".getBytes(StandardCharsets.UTF_8),"fixture-upload"));
        });
        check("document_original_and_private_access_http",()->{
            require(expect(200,request("GET","/documents/"+document1.id(),token1,null,null)).path("text").asText().equals(FIXTURE),"canonical original differs");
            expect(403,request("GET","/documents/"+document1.id(),token2,null,null));expect(200,request("GET","/documents/"+document1.id(),adminToken,null,null));
            require(request("GET","/documents/"+document1.id()+"/source",token1,null,null).body().equals(FIXTURE),"source download differs");
        });
    }
    /** 隔离库固定三身份／各两库／每库三份资料；旧新版、同名和注入与允许动作由版本化数据集保存。 */
    private static void testFixedDataset() {
        check("fixed_three_identity_six_base_eighteen_document_dataset",()->{
            var dataset=JSON.readTree(Files.readString(Path.of("scripts/fixtures/s11-core.json")));
            var actors=Map.of("admin",admin,"u1",user1,"u2",user2);
            var fixtureBases=new HashMap<String,KnowledgeBaseSnapshot>();
            var fixtureDocuments=new HashMap<String,Long>();
            fixtureBases.put("KB-ADMIN-A",adminBase);fixtureBases.put("KB-U1-A",base1);fixtureBases.put("KB-U2-A",base2);
            for(var entry:dataset.path("documents")){
                String owner=entry.path("owner").asText();String base=entry.path("base").asText();
                var actor=actors.get(owner);require(actor!=null,"unknown fixed identity");
                var target=fixtureBases.computeIfAbsent(base,id->bases.create(actor,id,"固定合成资料"));
                if(entry.path("id").asText().equals("DOC-U1-A01"))continue; // 已由真实multipart上传并核对原文。
                var created=documents.upload(actor,target.id(),entry.path("filename").asText(),"text/markdown",entry.path("text").asText().getBytes(StandardCharsets.UTF_8),entry.path("id").asText());
                fixtureDocuments.put(entry.path("id").asText(),created.id());
            }
            // 同一文档追加版本形成固定旧／新事实，不修改主问答资料的正式备份规则。
            for(var revision:dataset.path("revisions")){
                var id=fixtureDocuments.get(revision.path("document").asText());
                require(id!=null,"fixed revision document missing");
                documents.revise(user1,id,revision.path("from_version").asInt(),"同名笔记.md",revision.path("text").asText());
                require(jdbc.queryForObject("SELECT current_version FROM documents WHERE id=?",Integer.class,id)==revision.path("to_version").asInt(),"fixed revision mismatch");
            }
            require(fixtureBases.size()==6,"fixed base set incomplete");
            for(var actor:actors.values())require(bases.list(actor,ScopeRequest.self(),0,20).size()==2,"fixed owner base count");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM documents WHERE owner_user_id IN (?,?,?)",Integer.class,admin.userId(),user1.userId(),user2.userId())==18,"fixed document set incomplete");
            META.put("fixed_dataset_version",dataset.path("version").asText());
            // 候选召回／排序／引用支持仍须真实模型评测，种子成功不产生质量成绩。
            META.put("fixed_quality_status","NOT_RUN");
        });
    }
    private static void testNotesAndMemory() throws Exception {
        check("concurrent_approval_commits_one_document_and_operation",()->{
            var approval=personal.prepare(user1,base1.id(),"derived review","晨星 NVD-7319",List.of(new SourceDependency(base1.id(),document1.id(),1)));
            var pool=Executors.newFixedThreadPool(2);
            try{
                var a=pool.submit(()->personal.decide(user1,approval.approvalId(),true));var b=pool.submit(()->personal.decide(user1,approval.approvalId(),true));
                require(Objects.equals(a.get(30,TimeUnit.SECONDS).documentId(),b.get(30,TimeUnit.SECONDS).documentId()),"concurrent decisions created different documents");
                require(jdbc.queryForObject("SELECT COUNT(*) FROM operations WHERE operation_id=?",Integer.class,approval.operationId())==1,"operation duplicated");
            }finally{pool.shutdownNow();}
            denied("ACCESS_DENIED",()->personal.approval(admin,approval.approvalId()));
        });
        check("approval_target_version_change_rejected",()->{
            var approval=personal.prepare(user1,base1.id(),"pending review","晨星",List.of(new SourceDependency(base1.id(),document1.id(),1)));
            base1=bases.update(user1,base1.id(),base1.version(),base1.name(),"changed",true);
            denied("APPROVAL_CONFLICT",()->personal.decide(user1,approval.approvalId(),true));
        });
        check("derived_source_disable_blocks_read",()->{
            var approval=personal.prepare(admin,adminBase.id(),"admin derived","晨星",List.of(new SourceDependency(base1.id(),document1.id(),1)));
            long id=personal.decide(admin,approval.approvalId(),true).documentId();
            base1=bases.update(user1,base1.id(),base1.version(),base1.name(),base1.description(),false);
            denied("ACCESS_DENIED",()->documents.read(admin,id));
            base1=bases.update(user1,base1.id(),base1.version(),base1.name(),base1.description(),true);
        });
        check("memory_create_update_delete_and_admin_isolation",()->{
            var memory=personal.createMemory(user1,"回答简洁");
            denied("ACCESS_DENIED",()->personal.updateMemory(admin,memory.id(),memory.version(),"forbidden"));
            var changed=personal.updateMemory(user1,memory.id(),memory.version(),"回答详细");personal.deleteMemory(user1,changed.id(),changed.version());
            require(personal.memories(user1).isEmpty(),"deleted memory remains visible");
        });
    }
    private static void ingestCurrentDocument() {
        while(true){var lease=ingestion.claim("review-ingestion");if(lease.isEmpty())break;try{context.getBean(DocumentIngestionPipeline.class).execute(lease.get());}catch(RuntimeException e){ingestion.fail(lease.get(),e instanceof LabException lab?lab.code():"INGESTION_FAILED");throw e;}}
    }
    private static void testRag() throws Exception {
        check("real_embedding_es_sql_ingestion_and_directory",()->{
            ingestCurrentDocument();document1=documents.read(user1,document1.id()).document();
            require(document1.ingestionStatus().equals("READY")&&document1.activeProcessingRevision()!=null,"document not READY");
            require(expect(200,request("GET","/documents/"+document1.id()+"/sections",token1,null,null)).size()==3,"Markdown code heading parsed as section");
            require(expect(200,request("GET","/documents/"+document1.id()+"/chunks",token1,null,null)).size()>0,"no chunks");
        });
        check("real_deepseek_rag_answer_with_supported_citation",()->{
            JsonNode answer=expect(200,request("POST","/chat",token1,Map.of("question","项目代号是什么，验收编号是什么？","scope",ScopeRequest.self()),null));
            require(answer.path("status").asText().equals("SUCCESS"),"RAG not SUCCESS");require(!answer.path("mock").asBoolean(true),"RAG used mock");
            require(answer.path("answer").asText().contains("晨星")&&answer.path("answer").asText().contains("NVD-7319"),"answer missing fixture facts");
            require(answer.path("citations").size()>0,"no actual citations");
            String trace=answer.path("traceId").asText();expect(403,request("GET","/runs/"+trace,adminToken,null,null));
        });
        check("sse_program_statistics_progress_delta_done",()->{
            var response=request("POST","/chat/stream",token1,Map.of("question","统计我的文档","scope",ScopeRequest.self()),null);
            require(response.statusCode()==200,"SSE HTTP failed");String stream=response.body();
            require(stream.contains("event:progress")&&stream.contains("event:delta")&&stream.contains("event:done"),"SSE events incomplete");
            require(!stream.contains("event:error"),"SSE error");
        });
        check("foreign_document_never_retrieved_for_user2",()->{
            var vector=context.getBean(ModelGateway.class).embed(List.of("项目代号 晨星"),new ExecutionBudget(Duration.ofSeconds(60),2));
            var scope=context.getBean(KnowledgeCapabilityPort.class).authorize(user2,ScopeRequest.self());
            require(context.getBean(KnowledgeSearchPort.class).search(scope,"晨星",vector.vectors().get(0),vector.modelVersion()).isEmpty(),"foreign chunks in user2 results");
        });
    }
    private static TaskRequest taskRequest(String key) { return new TaskRequest("FAQ","晨星项目的备份规则",ScopeRequest.self(),List.of(document1.id()),key); }
    private static void testTaskState() throws Exception {
        check("task_idempotency_pause_resume_cancel_and_private_http",()->{
            var task=taskApplication.create(user1,taskRequest("task-state"));require(task.taskId()==taskApplication.create(user1,taskRequest("task-state")).taskId(),"task duplicated");
            expect(403,request("GET","/tasks/"+task.taskId(),adminToken,null,null));
            require(taskApplication.action(user1,task.taskId(),"pause").status().equals("PAUSED"),"pause failed");
            require(taskApplication.action(user1,task.taskId(),"resume").status().equals("QUEUED"),"resume failed");
            require(taskApplication.action(user1,task.taskId(),"cancel").status().equals("CANCELLED"),"cancel failed");
            denied("OPERATION_CONFLICT",()->taskApplication.action(user1,task.taskId(),"resume"));
        });
        check("task_fencing_rejects_late_worker_and_preserves_checkpoint_budget",()->{
            var task=taskApplication.create(user1,taskRequest("task-fencing"));var first=taskStore.claim("first-worker").orElseThrow();require(first.task().taskId()==task.taskId(),"unexpected claim");
            taskStore.reserveModelAttempt(first);taskStore.checkpoint(first,new TaskCheckpoint("research","已完成要点",List.of(new SourceDependency(base1.id(),document1.id(),1)),false));
            taskStore.action(user1,task.taskId(),"pause");require(!taskStore.renew(first),"late worker renewed");
            denied("STALE_EXECUTION",()->taskStore.checkpoint(first,new TaskCheckpoint("late","late",List.of(),false)));
            taskStore.action(user1,task.taskId(),"resume");var second=taskStore.claim("second-worker").orElseThrow();
            require(second.fencingToken()>first.fencingToken(),"fencing did not advance");require(second.task().modelAttempts()==1&&taskStore.checkpoints(second).size()==1,"resume reset budget/checkpoint");
            taskStore.action(user1,task.taskId(),"cancel");
        });
        check("notes_video_reports_unavailable",()->denied("MEDIA_CAPABILITY_UNAVAILABLE",()->taskApplication.create(user1,new TaskRequest("NOTES_VIDEO","review",ScopeRequest.self(),List.of(document1.id()),"video"))));
    }
    private static void testRealReport() throws Exception {
        check("real_faq_multi_role_worker_and_private_artifact",()->{
            var task=taskApplication.create(user1,taskRequest("real-faq"));
            var worker=new ReportTaskWorker(taskStore,context.getBean(KnowledgeCapabilityPort.class),context.getBean(ModelGateway.class),context.getBean(PlanValidator.class));
            try{worker.scan();long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();TaskSnapshot state;
                do{Thread.sleep(500);state=taskApplication.read(user1,task.taskId());}while(Set.of("QUEUED","RUNNING").contains(state.status())&&System.nanoTime()<deadline);
                require(state.status().equals("SUCCEEDED"),"FAQ state="+state.status()+", code="+state.errorCode());require(state.completedSteps()==3&&state.modelAttempts()>=3,"role checkpoints incomplete");
                var artifact=taskApplication.artifact(user1,state.artifactId());require(!artifact.content().isBlank(),"artifact blank");
                denied("ACCESS_DENIED",()->taskApplication.artifact(admin,artifact.artifactId()));
                require(request("GET","/artifacts/"+artifact.artifactId(),token1,null,null).statusCode()==200,"artifact download failed");
                base1=bases.update(user1,base1.id(),base1.version(),base1.name(),base1.description(),false);
                denied("ACCESS_DENIED",()->taskApplication.artifact(user1,artifact.artifactId()));
                base1=bases.update(user1,base1.id(),base1.version(),base1.name(),base1.description(),true);
            }finally{worker.close();}
        });
    }
    private static void testResearchReport() {
        check("real_research_report_multi_role_worker_and_artifact",()->{
            var request=new TaskRequest("RESEARCH_REPORT","说明晨星项目备份规则",ScopeRequest.self(),List.of(document1.id()),"focused-research");
            var task=taskApplication.create(user1,request);
            var worker=new ReportTaskWorker(taskStore,context.getBean(KnowledgeCapabilityPort.class),context.getBean(ModelGateway.class),context.getBean(PlanValidator.class));
            try{worker.scan();long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();TaskSnapshot state;
                do{Thread.sleep(500);state=taskApplication.read(user1,task.taskId());}while(Set.of("QUEUED","RUNNING").contains(state.status())&&System.nanoTime()<deadline);
                require(state.status().equals("SUCCEEDED"),"research state="+state.status()+", code="+state.errorCode());
                require(state.completedSteps()==3&&state.modelAttempts()>=3,"research roles incomplete");
                require(!taskApplication.artifact(user1,state.artifactId()).content().isBlank(),"research artifact blank");
            } finally {worker.close();}
        });
    }
    /** 完整传输断言使用程序统计，不购买模型调用。 */
    private static void testSseDiagnostics() {
        check("focused_real_http_sse_complete_transfer",()->{
            var logger=(org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getLogger("org.springframework.security.web.access.ExceptionTranslationFilter");
            var oldLevel=logger.getLevel();logger.setLevel(org.apache.logging.log4j.Level.TRACE);
            try {
                var builder=HttpRequest.newBuilder(URI.create(origin+"/api/v1/chat/stream")).timeout(Duration.ofSeconds(15)).header("Authorization","Bearer "+token1)
                    .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(toJson(Map.of("question","统计文档","scope",ScopeRequest.self())),StandardCharsets.UTF_8));
                var response=HTTP.send(builder.build(),information->new HttpResponse.BodySubscriber<String>() {
                    final CompletableFuture<String> result=new CompletableFuture<>();final ByteArrayOutputStream body=new ByteArrayOutputStream();
                    public CompletionStage<String> getBody(){return result;}
                    public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
                    public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers){byte[] bytes=new byte[buffer.remaining()];buffer.get(bytes);body.writeBytes(bytes);}}
                    public void onError(Throwable error){META.put("sse_transfer_error",safeReason(error));META.put("sse_transfer_error_types",error.getClass().getSimpleName()+"/"+(error.getCause()==null?"none":error.getCause().getClass().getSimpleName()));result.complete(body.toString(StandardCharsets.UTF_8));}
                    public void onComplete(){result.complete(body.toString(StandardCharsets.UTF_8));}
                });
                String body=response.body();META.put("sse_http_status",response.statusCode());META.put("sse_contains_done",body.contains("event:done"));META.put("sse_body_bytes",body.getBytes(StandardCharsets.UTF_8).length);
                require(response.statusCode()==200&&body.contains("event:progress")&&body.contains("event:delta")&&body.contains("event:done"),"SSE response status/events incomplete");
                require(!META.containsKey("sse_transfer_error"),"SSE events sent but HTTP chunked transfer terminated abnormally");
            } finally {logger.setLevel(oldLevel);}
        });
    }
    private static void testDefects() throws Exception {
        testIndexCleanup();
        check("regression_repeated_pause_resume_keeps_task_runnable",()->{
            var task=taskApplication.create(user1,taskRequest("resume-limit"));
            for(int i=0;i<3;i++){require(taskStore.claim("resume-worker-"+i).isPresent(),"claim failed before third pause");taskStore.action(user1,task.taskId(),"pause");taskStore.action(user1,task.taskId(),"resume");}
            require(taskStore.claim("resume-worker-4").isPresent(),"task remains QUEUED forever after three normal pauses (attempt=3)");taskStore.action(user1,task.taskId(),"cancel");
        });
        // 前一项失败时也清理其待运行状态，避免后续领取其他测试的任务。
        jdbc.update("UPDATE ai_tasks SET status='CANCELLED' WHERE status='QUEUED' AND requester_user_id=?",user1.userId());
        check("regression_abnormal_lease_recovery_remains_bounded",()->{
            var task=taskApplication.create(user1,taskRequest("abnormal-lease"));
            var first=taskStore.claim("lost-worker").orElseThrow();long fencing=first.fencingToken();
            for(int recovery=0;recovery<3;recovery++){
                jdbc.update("UPDATE ai_tasks SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",task.taskId());
                var lease=taskStore.claim("recovery-worker").orElseThrow();
                require(lease.task().taskId()==task.taskId()&&lease.fencingToken()>fencing,"abnormal recovery did not fence old executor");fencing=lease.fencingToken();
            }
            jdbc.update("UPDATE ai_tasks SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",task.taskId());
            require(taskStore.claim("recovery-worker").isEmpty(),"abnormal recovery budget ignored");
            require(taskStore.read(user1,task.taskId()).status().equals("FAILED"),"expired over-budget task lacks terminal state");
            denied("STALE_EXECUTION",()->taskStore.checkpoint(first,new TaskCheckpoint("late","late",List.of(),false)));
        });
        check("regression_exhausted_model_budget_leaves_no_queued_task",()->{
            var task=taskApplication.create(user1,taskRequest("exhausted-budget"));
            jdbc.update("UPDATE ai_tasks SET model_attempts=10 WHERE id=?",task.taskId());
            require(taskStore.claim("budget-worker").isEmpty(),"exhausted task received execution opportunity");
            require(taskStore.read(user1,task.taskId()).status().equals("FAILED"),"exhausted task stranded in QUEUED");
        });
        check("regression_document_delete_supported_when_base_disabled",()->{
            var document=documents.upload(user2,base2.id(),"disabled-delete.txt","text/plain","test delete".getBytes(StandardCharsets.UTF_8),"disabled-delete");
            base2=bases.update(user2,base2.id(),base2.version(),base2.name(),base2.description(),false);
            documents.delete(user2,document.id(),document.documentVersion());
            require(jdbc.queryForObject("SELECT deleted FROM documents WHERE id=?",Boolean.class,document.id()),"document not deleted");
        });
        check("regression_owner_can_delete_derived_note_after_source_revoked",()->{
            var sourceBase=bases.create(user1,"revoked source","");
            var sourceDoc=documents.upload(user1,sourceBase.id(),"source.txt","text/plain","合成来源".getBytes(StandardCharsets.UTF_8),"revoked-source");
            var approval=personal.prepare(user1,base1.id(),"derived","合成笔记",List.of(new SourceDependency(sourceBase.id(),sourceDoc.id(),1)));
            long noteId=personal.decide(user1,approval.approvalId(),true).documentId();
            bases.update(user1,sourceBase.id(),sourceBase.version(),sourceBase.name(),"",false);
            denied("ACCESS_DENIED",()->documents.read(user1,noteId));
            denied("ACCESS_DENIED",()->documents.delete(admin,noteId,1));
            documents.delete(user1,noteId,1);
            require(jdbc.queryForObject("SELECT deleted FROM documents WHERE id=?",Boolean.class,noteId),"owner unable to delete revoked derived note");
        });
        check("regression_cleanup_lease_fences_late_completion",()->{
            var store=context.getBean(IndexCleanupStorePort.class);
            var first=store.claim("cleanup-lost").orElseThrow();
            jdbc.update("UPDATE outbox_events SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?",first.eventId());
            var next=store.claim("cleanup-recovery").orElseThrow();
            require(next.eventId()==first.eventId()&&next.fencingToken()>first.fencingToken(),"cleanup lease recovery incorrect");
            store.finish(first,true);
            require(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,first.eventId()).equals("PROCESSING"),"late cleanup marked event DONE");
            store.finish(next,false);drainCleanup();
            require(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?",String.class,first.eventId()).equals("DONE"),"recovered cleanup not completed");
        });
        check("regression_report_source_consistent_after_document_revision",()->{
            var task=taskApplication.create(user1,taskRequest("source-revision"));var lease=taskStore.claim("source-worker").orElseThrow();
            taskStore.checkpoint(lease,new TaskCheckpoint("research","旧版本备份规则是每周三",List.of(new SourceDependency(base1.id(),document1.id(),1)),false));
            documents.revise(user1,document1.id(),1,"review.md",FIXTURE.replace("每周三","每周五"));
            taskStore.action(user1,task.taskId(),"pause");taskStore.action(user1,task.taskId(),"resume");
            var worker=new ReportTaskWorker(taskStore,context.getBean(KnowledgeCapabilityPort.class),context.getBean(ModelGateway.class),context.getBean(PlanValidator.class));
            try {
                worker.scan();long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();TaskSnapshot state;
                do{Thread.sleep(500);state=taskApplication.read(user1,task.taskId());}while(Set.of("QUEUED","RUNNING").contains(state.status())&&System.nanoTime()<deadline);
                require(state.status().equals("SUCCEEDED"),"resumed report state="+state.status()+", code="+state.errorCode());
                var artifact=taskApplication.artifact(user1,state.artifactId());
                META.put("resumed_report_source_versions",artifact.sourceDependencies().stream().map(SourceDependency::documentVersion).toList());
                // 汇合使用了 v1 检查点，所以最终产物必须保留这项实际来源。
                require(artifact.sourceDependencies().stream().anyMatch(s->s.documentId()==document1.id()&&s.documentVersion()==1),"resumed report uses v1 checkpoint but artifact records only v2 sources");
            } finally {worker.close();}
        });
        check("regression_malformed_nested_id_returns_400",()->expect(400,request("POST","/tasks",token1,Map.of("taskType","FAQ","topic","review","documentIds",Arrays.asList((Object)null)),"null-document")));
        check("regression_deleted_es_chunks_are_cleaned_by_outbox",()->{
            documents.delete(user1,document1.id(),2);
            drainCleanup();
            int pending=jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='DELETE_DOCUMENT' AND status='PENDING'",Integer.class);
            long remaining=es.count(c->c.index(testIndex).query(q->q.term(t->t.field("documentId").value(document1.id())))).count();
            META.put("deleted_document_es_chunk_count",remaining);
            require(pending==0,"DELETE_DOCUMENT outbox remains PENDING; no delete-event consumer exists");
            require(remaining==0,"deleted document still occupies ES candidate slots");
        });
    }

    /** 临时索引内测试真实重处理激活、严格旧代次清理和库删除，不触碰配置原索引。 */
    @SuppressWarnings("unchecked")
    private static void testIndexCleanup() throws Exception {
        check("regression_reprocess_prunes_old_generation_and_preserves_future",()->{
            var hits=es.search(s->s.index(testIndex).query(q->q.term(t->t.field("documentId").value(document1.id()))),Map.class).hits().hits();
            require(!hits.isEmpty(),"current indexed fixture absent");
            var future=new LinkedHashMap<String,Object>(hits.get(0).source());
            future.put("processingRevision",999L);future.put("chunkId","future-generation-fixture");
            es.index(i->i.index(testIndex).id("future-generation-fixture").document(future));
            ingestion.reprocess(user1,document1.id());
            long ingestionId=jdbc.queryForObject("SELECT id FROM document_ingestions WHERE document_id=? AND document_version=1 AND processing_revision=2",Long.class,document1.id());
            // 单独领取本次 fixture，其他验收资料的自动 Worker 仍关闭。
            jdbc.update("UPDATE document_ingestions SET status='PROCESSING',attempt=1,worker_id='review-reprocess',fencing_token=1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=?",ingestionId);
            var lease=new IngestionLease(ingestionId,document1.id(),1,2,user1,"review-reprocess",1,"review.md","md",FIXTURE);
            context.getBean(DocumentIngestionPipeline.class).execute(lease);
            drainCleanup();
            require(es.count(c->c.index(testIndex).query(q->q.bool(b->b.filter(f->f.term(t->t.field("documentId").value(document1.id()))).filter(f->f.term(t->t.field("processingRevision").value(1)))))).count()==0,"old active generation remains indexed");
            require(es.count(c->c.index(testIndex).query(q->q.bool(b->b.filter(f->f.term(t->t.field("documentId").value(document1.id()))).filter(f->f.term(t->t.field("processingRevision").value(2)))))).count()>0,"new active generation deleted");
            require(es.exists(e->e.index(testIndex).id("future-generation-fixture")).value(),"cleanup crossed immutable revision boundary");
        });
        check("regression_base_delete_cleans_all_indexed_documents",()->{
            var temporaryBase=bases.create(user2,"cleanup base","");
            var doc=documents.upload(user2,temporaryBase.id(),"cleanup.txt","text/plain","合成删除数据".getBytes(StandardCharsets.UTF_8),"cleanup-base");
            var hit=es.search(s->s.index(testIndex).query(q->q.term(t->t.field("documentId").value(document1.id()))),Map.class).hits().hits().get(0);
            var source=new LinkedHashMap<String,Object>(hit.source());
            source.put("knowledgeBaseId",temporaryBase.id());source.put("ownerUserId",user2.userId());source.put("documentId",doc.id());source.put("chunkId","base-delete-fixture");
            es.index(i->i.index(testIndex).id("base-delete-fixture").document(source));es.indices().refresh(i->i.index(testIndex));
            bases.delete(user2,temporaryBase.id(),temporaryBase.version());drainCleanup();
            require(es.count(c->c.index(testIndex).query(q->q.term(t->t.field("knowledgeBaseId").value(temporaryBase.id())))).count()==0,"deleted base remains indexed");
            require(jdbc.queryForObject("SELECT status FROM outbox_events WHERE event_type='DELETE_BASE' AND resource_id=?",String.class,temporaryBase.id()).equals("DONE"),"base cleanup event not complete");
        });
    }

    /** 自动 Worker 关闭时，明确触发正式清理端口；每轮保持单批且总轮数有界。 */
    private static void drainCleanup(){
        var worker=context.getBean(com.example.ailab.ai.rag.IndexCleanupWorker.class);
        for(int batch=0;batch<40;batch++)if(!worker.executeNext())return;
        throw new AssertionError("cleanup exceeded bounded validation batches");
    }
}
