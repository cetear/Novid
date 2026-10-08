package com.example.ailab.app;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.example.ailab.ai.rag.DocumentIngestionPipeline;
import com.example.ailab.ai.memory.SessionHistoryService;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.business.application.AccountApplicationService;
import com.example.ailab.business.application.KnowledgeBaseApplicationService;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.data.repository.SqlSupport;
import com.example.ailab.data.search.ElasticsearchClientFactory;
import com.example.ailab.data.search.SearchProperties;
import com.fasterxml.jackson.databind.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sun.net.httpserver.HttpServer;

/** S01 真实 MySQL、正式 HTTP、单目标模型专项；只使用合成资料与本次独立索引。 */
public final class SessionNativeValidation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final Map<String, Object> META = new LinkedHashMap<>();
    private static final List<Long> FIXTURE_USERS = new ArrayList<>();
    private static final Map<String, List<Long>> EXISTING_IDS = new LinkedHashMap<>();
    private static final String PASSWORD = "S01-" + UUID.randomUUID() + "-password";
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String TEST_SCHEMA = "novid_s01_" + SUFFIX;
    private static final String TEST_INDEX = "novid_s01_" + SUFFIX;
    private static final String FIXTURE_TEXT = "# 合成会话验收资料\n\n验证项目代号为晨星。\n备份规则是每周三执行一次，保留七份备份。\n第二篇资料解释：备份轮换用于限制磁盘占用。\n";
    private static Map<String, Object> defaults;
    private static String configuredUrl, testedUrl, origin, ownerToken, userToken, adminToken;
    private static boolean schemaCreated, indexCreated;
    private static boolean focused;
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RestClientTransport esTransport;
    private static ElasticsearchClient es;
    private static UserContext owner;
    private static long baseId, documentId, sessionId, sessionVersion;

    /** 单项验收可抛异常，统一记录并在最终结果中保持失败事实。 */
    @FunctionalInterface private interface Checked {
        /** 执行一项确定的检查，不自行捕获异常冒称通过。 */
        void run() throws Exception;
    }

    /** 使用正式白名单加载器；值只保存在内存，异常只输出类型／固定断言信息。 */
    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        defaults = new LinkedHashMap<>(LocalEnvironmentLoader.read(Path.of(args.length == 0 ? ".env" : args[0])));
        focused = args.length > 1 && args[1].equals("--focused");
        configuredUrl = configuration("DB_URL");
        META.put("date", "2026-10-04 Asia/Shanghai");
        META.put("model_evidence", focused ? "local HTTP provider stub for chat; actual embedding of synthetic fixture; no real chat calls"
                : "real configured primary; synthetic input; output limit 512");
        META.put("worker_scans", 0);
        META.put("application_jar_sha256", System.getProperty("validation.application-jar-sha256", "not supplied"));
        META.put("scope", focused ? "local protocol/TCP/source revocation supplement; no real chat calls" : "full S01 native validation");
        try {
            require("real".equals(configuration("MODEL_MODE")), "native validation requires configured real mode");
            selectDatabase();
            startApplication();
            initializeIndex();
            createAccounts();
            createAndIngestOwnFixture();
            testSessionsAndLegacy();
            if (focused) {
                // 已通过的真实模型两轮与摘要不重复收费；补验所需历史明确使用仓库 fixture。
                sessionVersion = seedTurns(owner, sessionId, 2, null);
            } else {
                testRealTwoTurnsAndRestart();
                testIsolationAndVersions();
                testConcurrencyAndRelease();
                testRealSummaryAndContextResets();
                testRoleDowngrade();
            }
            testLocalProtocolFailures();
            testRevocationAndDeletion();
        } catch (Throwable error) {
            RESULTS.add(Map.of("case", "setup_or_execution", "status", "FAIL", "reason", safeReason(error)));
        } finally {
            closeApplication();
            cleanupFixtures();
            long passed = RESULTS.stream().filter(r -> r.get("status").equals("PASS")).count();
            META.put("passed", passed);
            META.put("failed", RESULTS.size() - passed);
            Files.createDirectories(Path.of("var/stage-S01"));
            Files.writeString(Path.of(focused ? "var/stage-S01/native-focused-results.json" : "var/stage-S01/native-results.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("metadata", META, "cases", RESULTS)));
            System.out.println(JSON.writeValueAsString(META));
        }
        if (RESULTS.stream().anyMatch(r -> r.get("status").equals("FAIL"))) System.exit(1);
    }

    /** 显式环境变量优先，避免凭证写入命令行或打印的配置映射。 */
    private static String configuration(String key) {
        String value = System.getenv(key);
        if (value == null) value = Objects.toString(defaults.get(key), null);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing required configuration key");
        return value;
    }

    /** 新随机 schema 优先；1044 仅切换为精确 ID 清理模式，绝不清空已配置库。 */
    private static void selectDatabase() throws Exception {
        try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) {
            META.put("mysql_version", connection.getMetaData().getDatabaseProductVersion());
            try {
                statement.executeUpdate("CREATE DATABASE `" + TEST_SCHEMA + "` CHARACTER SET utf8mb4");
                schemaCreated = true;
            } catch (SQLException error) {
                if (error.getErrorCode() != 1044) throw error;
            }
        }
        URI address = URI.create(configuredUrl.substring(5));
        testedUrl = schemaCreated ? "jdbc:mysql://" + address.getRawAuthority() + "/" + TEST_SCHEMA + (address.getRawQuery() == null ? "" : "?" + address.getRawQuery()) : configuredUrl;
        META.put("isolation", schemaCreated ? "new random database and ES index" : "configured database; exact new fixture user IDs; random ES index");
        if (!schemaCreated) {
            // 只保留主键作最终存在性核验，不读取原有私人正文，也不将这些 ID 写证据。
            try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) {
                for (String table : List.of("users", "knowledge_bases", "documents", "ai_tasks", "profile_memories")) {
                    var ids = new ArrayList<Long>();
                    try (ResultSet rows = statement.executeQuery("SELECT id FROM " + table)) { while (rows.next()) ids.add(rows.getLong(1)); }
                    EXISTING_IDS.put(table, List.copyOf(ids));
                }
            }
        }
    }

    /** 临时正式应用只绑定随机本机端口；三类自动扫描均关闭，不停止用户进程。 */
    private static void startApplication(String... extraArguments) {
        SpringApplication application = new SpringApplication(LabApplication.class);
        application.setDefaultProperties(defaults);
        var arguments = new ArrayList<>(List.of("--spring.datasource.url=" + testedUrl, "--server.port=0", "--server.address=127.0.0.1",
                "--lab.bootstrap.enabled=false", "--lab.search.enabled=true", "--lab.search.index=" + TEST_INDEX,
                "--lab.ingestion.worker-enabled=false", "--lab.task.worker-enabled=false", "--lab.model.models.primary.output-limit=512",
                "--lab.model.models.backup.enabled=false",
                "--logging.level.root=OFF", "--spring.main.banner-mode=off"));
        arguments.addAll(List.of(extraArguments));
        context = application.run(arguments.toArray(String[]::new));
        origin = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        jdbc = context.getBean(JdbcTemplate.class);
    }

    /** 只建立本次随机索引；正式原索引保留且不进行维护队列消费。 */
    private static void initializeIndex() throws Exception {
        SearchProperties config = context.getBean(SearchProperties.class);
        esTransport = new RestClientTransport(ElasticsearchClientFactory.create(config), new JacksonJsonpMapper());
        es = new ElasticsearchClient(esTransport);
        META.put("es_version", es.info().version().number());
        require(!es.indices().exists(r -> r.index(TEST_INDEX)).value(), "random test index already exists");
        context.getBean(KnowledgeIndexPort.class).initialize();
        indexCreated = true;
        check("real_mysql_v1_v5_and_formal_http_boot", () -> {
            require(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='5' AND success=TRUE", Integer.class) == 1, "V5 migration not applied");
            require(request("GET", "/sessions", null, null, null).statusCode() == 401, "anonymous session access allowed");
        });
    }

    /** 构造唯一本次账户，不调用空库 bootstrap，不读取或修改已有管理员。 */
    private static void createAccounts() throws Exception {
        ValidationSql sql = ValidationSql.from(context);
        String hash = new BCryptPasswordEncoder(12).encode(PASSWORD);
        for (String role : List.of("USER", "USER", "ADMIN", "ADMIN")) {
            String name = "s01_" + SUFFIX + "_" + FIXTURE_USERS.size();
            long id = sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,?,FALSE)", name, hash, role);
            FIXTURE_USERS.add(id);
        }
        ownerToken = login(0);
        userToken = login(1);
        adminToken = login(2);
        owner = context.getBean(AccountApplicationService.class).authenticate(ownerToken);
    }

    /** 真实认证必须经正式网络入口，令牌只保存在变量且不写证据。 */
    private static String login(int index) throws Exception {
        return expect(200, request("POST", "/auth/login", null, Map.of("username", "s01_" + SUFFIX + "_" + index, "password", PASSWORD), null)).path("token").asText();
    }

    /** 只显式生成自己的 PROCESSING 批次；不创建可被用户 Worker 扫描的 RECEIVED 队列项。 */
    private static void createAndIngestOwnFixture() throws Exception {
        baseId = context.getBean(KnowledgeBaseApplicationService.class).create(owner, "S01 synthetic " + SUFFIX, "").id();
        long[] values = new long[2];
        var transaction = new TransactionTemplate(context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        transaction.executeWithoutResult(status -> {
            ValidationSql sql = ValidationSql.from(context);
            values[0] = sql.insert("INSERT INTO documents(knowledge_base_id,owner_user_id,title,format) VALUES(?,?,?,'md')", baseId, owner.userId(), "s01.md");
            jdbc.update("INSERT INTO document_versions(document_id,document_version,raw_text,checksum) VALUES(?,1,?,?)", values[0], FIXTURE_TEXT, SqlSupport.hash(FIXTURE_TEXT));
            values[1] = sql.insert("INSERT INTO document_ingestions(document_id,document_version,processing_revision,actor_user_id,status,attempt,worker_id,fencing_token,lease_until) VALUES(?,1,1,?,'PROCESSING',1,'s01-explicit-fixture',1,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND))", values[0], owner.userId());
        });
        documentId = values[0];
        check("real_es_embedding_explicit_own_fixture_ingestion", () -> {
            context.getBean(DocumentIngestionPipeline.class).execute(new IngestionLease(values[1], documentId, 1, 1, owner, "s01-explicit-fixture", 1, "s01.md", "md", FIXTURE_TEXT));
            require("READY".equals(jdbc.queryForObject("SELECT ingestion_status FROM document_versions WHERE document_id=? AND document_version=1", String.class, documentId)), "fixture ingestion not READY");
        });
    }

    /** 创建幂等与旧 JSON/SSE 兼容使用程序统计，避免增加真实模型费用。 */
    private static void testSessionsAndLegacy() throws Exception {
        check("http_private_session_creation_and_idempotency", () -> {
            JsonNode first = expect(201, request("POST", "/sessions", ownerToken, Map.of("title", "合成两轮"), "s01-create"));
            JsonNode again = expect(201, request("POST", "/sessions", ownerToken, Map.of("title", "合成两轮"), "s01-create"));
            sessionId = first.path("id").asLong(); sessionVersion = first.path("version").asLong();
            require(sessionId > 0 && again.path("id").asLong() == sessionId, "session creation duplicated");
            expect(409, request("POST", "/sessions", ownerToken, Map.of("title", "不同请求"), "s01-create"));
        });
        check("http_unknown_identity_and_partial_session_fields_rejected", () -> {
            expect(400, request("POST", "/sessions", ownerToken, Map.of("title", "伪造", "userId", FIXTURE_USERS.get(1)), "forged"));
            expect(400, request("POST", "/chat", ownerToken, Map.of("question", "统计", "sessionId", sessionId), null));
        });
        check("legacy_json_without_session", () -> expect(200, request("POST", "/chat", ownerToken, Map.of("question", "统计我的文档"), null)));
        check("legacy_sse_without_session_normal_eof", () -> {
            var response = request("POST", "/chat/stream", ownerToken, Map.of("question", "统计我的文档"), null);
            require(response.statusCode() == 200 && response.body().contains("event:done") && !response.body().contains("event:error"), "legacy SSE incomplete");
        });
    }

    /** 第二轮随机码既不在资料中也不在第二轮请求中，真实返回才能证明第一轮上下文到达模型。 */
    private static void testRealTwoTurnsAndRestart() throws Exception {
        String marker = "S01CTX" + SUFFIX.toUpperCase(Locale.ROOT);
        check("real_model_first_turn_saved_with_sources", () -> {
            JsonNode result = chat("请说明合成资料的备份规则。本轮约定代号为 " + marker + "；请在答案里原样复述这个代号，下一轮会继续询问。", selectedScope(), sessionId, sessionVersion);
            require(result.path("status").asText().equals("SUCCESS") && !result.path("mock").asBoolean(true), "first answer not real success");
            require(result.path("answer").asText().contains(marker), "first answer did not reproduce marker");
            require(result.path("citations").size() > 0, "first answer had no sources");
            sessionVersion = result.path("sessionVersion").asLong();
            JsonNode history = messages(ownerToken, sessionId);
            require(history.size() == 2 && history.get(0).path("content").asText().contains(marker), "first turn not persisted as pair");
        });
        check("real_restart_persisted_history_read_http", () -> {
            closeApplication(); startApplication();
            JsonNode history = messages(ownerToken, sessionId);
            require(history.size() == 2 && history.get(0).path("content").asText().contains(marker), "restart lost history");
        });
        check("real_model_second_turn_reads_first_turn", () -> {
            String secondQuestion = "沿用刚才合成资料的备份规则，请先原样重复我在第一轮约定的代号，再解释为何保留七份备份。";
            require(!secondQuestion.contains(marker) && !FIXTURE_TEXT.contains(marker), "second turn accidentally includes marker");
            JsonNode result = chat(secondQuestion, selectedScope(), sessionId, sessionVersion);
            require(result.path("status").asText().equals("SUCCESS") && !result.path("mock").asBoolean(true), "second answer not real success");
            require(result.path("answer").asText().contains(marker), "second real answer omitted prior-turn marker");
            sessionVersion = result.path("sessionVersion").asLong();
            JsonNode history = messages(ownerToken, sessionId);
            require(history.size() == 4 && history.get(3).path("content").asText().contains(marker), "second turn not persisted");
            META.put("real_two_turns", true);
        });
        check("model_inputs_do_not_auto_write_personal_preferences", () -> require(jdbc.queryForObject("SELECT COUNT(*) FROM profile_memories WHERE user_id=?", Integer.class, owner.userId()) == 0, "data automatically saved as preference"));
    }

    /** 普通旁观者和另一个管理员的 ID、分页列表、消息、chat 和删除都必须隔离。 */
    private static void testIsolationAndVersions() throws Exception {
        for (String token : List.of(userToken, adminToken)) {
            check(token.equals(userToken) ? "http_other_user_private_session_matrix" : "http_other_admin_private_session_matrix", () -> {
                expect(403, request("GET", "/sessions/" + sessionId, token, null, null));
                expect(403, request("GET", "/sessions/" + sessionId + "/messages", token, null, null));
                expect(403, request("DELETE", "/sessions/" + sessionId + "?version=" + sessionVersion, token, null, null));
                expect(403, request("POST", "/chat", token, chatBody("统计", selectedScope(), sessionId, sessionVersion), null));
                require(expect(200, request("GET", "/sessions?page=0&size=20", token, null, null)).isEmpty(), "foreign session listed");
            });
        }
        check("http_stale_chat_and_delete_version_rejected", () -> {
            expect(409, request("POST", "/chat", ownerToken, chatBody("统计", selectedScope(), sessionId, 1), null));
            expect(409, request("DELETE", "/sessions/" + sessionId + "?version=1", ownerToken, null, null));
        });
    }

    /** 网络并发真实事务只有一个提交；显式端口执行权探针与真实 HTTP 验收分别记录。 */
    private static void testConcurrencyAndRelease() throws Exception {
        check("http_same_session_parallel_cas_one_pair", () -> {
            long id = createSession("并发"), version = 1;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);
            try {
                Callable<HttpResponse<String>> action = () -> { start.await(); return request("POST", "/chat", ownerToken, chatBody("统计我的文档", selectedScope(), id, version), null); };
                Future<HttpResponse<String>> left = pool.submit(action), right = pool.submit(action); start.countDown();
                List<Integer> statuses = new ArrayList<>(List.of(left.get().statusCode(), right.get().statusCode())); Collections.sort(statuses);
                require(statuses.equals(List.of(200, 409)), "parallel requests were not one success and one conflict");
                JsonNode history = messages(ownerToken, id);
                require(history.size() == 2 && history.get(0).path("seq").asLong() == 1 && history.get(1).path("seq").asLong() == 2, "duplicate or interleaved sequence");
            } finally { pool.shutdownNow(); }
        });
        check("real_repository_abort_and_expired_lease_fence", () -> {
            long id = createSession("执行权"); SessionStorePort store = context.getBean(SessionStorePort.class);
            SessionLease first = store.begin(owner, id, 1, selectedScope()); store.abort(owner, first);
            require(store.messages(owner, id, 0, 20).isEmpty(), "abort saved fake answer");
            SessionSnapshot afterAbort = store.read(owner, id); require(afterAbort.version() == 2, "abort did not advance version");
            SessionLease expired = store.begin(owner, id, 2, selectedScope());
            jdbc.update("UPDATE sessions SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=? AND user_id=?", id, owner.userId());
            SessionLease recovered = store.begin(owner, id, 2, selectedScope());
            require(!expired.executionId().equals(recovered.executionId()), "expired execution not replaced");
            try { store.assertActive(owner, expired); throw new AssertionError("late execution accepted"); } catch (com.example.ailab.contract.error.LabException expected) { require(expected.code().equals("STALE_EXECUTION"), "unexpected stale execution error"); }
            store.abort(owner, recovered);
            require(store.messages(owner, id, 0, 20).isEmpty(), "expired/aborted execution saved fake success");
        });
    }

    /** 八轮仓库合成历史明确标为 fixture；正式 HTTP 随后触发真实模型摘要与真实回答。 */
    private static void testRealSummaryAndContextResets() throws Exception {
        JsonNode memory = expect(200, request("POST", "/memories", ownerToken, Map.of("content", "请使用中文回答"), null));
        long summaryId = createSession("真实摘要"), summaryVersion = seedTurns(owner, summaryId, 8, null);
        META.put("summary_fixture_seeded_turns", 8);
        check("real_model_summary_with_source_coverage_and_byte_bound", () -> {
            JsonNode answer = chat("根据前面的合成资料对话，请再次说明备份规则与轮换目的。", selectedScope(), summaryId, summaryVersion);
            require(answer.path("status").asText().equals("SUCCESS") && !answer.path("mock").asBoolean(true), "summary answer not real success");
            String summary = jdbc.queryForObject("SELECT summary_content FROM sessions WHERE id=? AND user_id=?", String.class, summaryId, owner.userId());
            require(summary != null && !summary.isBlank() && summary.getBytes(StandardCharsets.UTF_8).length <= 2000, "real summary missing or oversized");
            require(jdbc.queryForObject("SELECT summary_covered_through_seq FROM sessions WHERE id=?", Long.class, summaryId) > 0, "summary has no coverage sequence");
            require(jdbc.queryForObject("SELECT JSON_LENGTH(summary_source_json) FROM sessions WHERE id=?", Integer.class, summaryId) > 0, "summary has no immutable source dependencies");
            META.put("real_model_summary", true);
        });
        check("http_preference_delete_clears_derived_summary_and_window", () -> {
            expect(200, request("DELETE", "/memories/" + memory.path("id").asLong() + "?version=1", ownerToken, null, null));
            require(jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND summary_content IS NULL AND context_floor_seq=next_seq-1 AND execution_id IS NULL", Integer.class, summaryId) == 1, "preference deletion kept derived summary/window");
            require(expect(200, request("GET", "/memories", ownerToken, null, null)).isEmpty(), "deleted preference still readable");
            SessionStorePort store = context.getBean(SessionStorePort.class);
            SessionLease lease = store.begin(owner, summaryId, store.read(owner, summaryId).version(), selectedScope());
            try {
                var loaded = context.getBean(SessionHistoryService.class).load(owner, lease, new ExecutionBudget(Duration.ofSeconds(60), 10));
                require(loaded.messages().isEmpty() && loaded.summary() == null, "deleted preference still has old derived model context");
            } finally { store.abort(owner, lease); }
        });
        check("http_scope_change_rebuilds_legal_model_window", () -> {
            long id = createSession("范围变化"), version = seedTurns(owner, id, 3, new SessionSummary("合成摘要，仅用于范围清理断言", 2, List.of(fixtureDependency())));
            ScopeRequest empty = new ScopeRequest(ScopeRequest.Mode.SELECTED, List.of(), null);
            JsonNode answer = chat("统计我的文档", empty, id, version);
            require(answer.path("answer").asText().contains("文档总数：0"), "empty scope retained old statistics");
            require(jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND summary_content IS NULL AND context_floor_seq=6", Integer.class, id) == 1, "scope change retained prior derived context");
            SessionStorePort store = context.getBean(SessionStorePort.class);
            SessionLease lease = store.begin(owner, id, answer.path("sessionVersion").asLong(), empty);
            try {
                var loaded = context.getBean(SessionHistoryService.class).load(owner, lease, new ExecutionBudget(Duration.ofSeconds(60), 10));
                require(loaded.dependencies().isEmpty() && loaded.selected().stream().allMatch(m -> m.seq() > 6), "old scope entered model window");
            } finally { store.abort(owner, lease); }
        });
        sessionVersion = expect(200, request("GET", "/sessions/" + sessionId, ownerToken, null, null)).path("version").asLong();
    }

    /** 合成历史通过正式仓库原子完整对写入；不把这些测试文本当真实模型回答统计。 */
    private static long seedTurns(UserContext actor, long id, int count, SessionSummary lastSummary) {
        SessionStorePort store = context.getBean(SessionStorePort.class);
        long version = store.read(actor, id).version();
        for (int turn = 0; turn < count; turn++) {
            SessionLease lease = store.begin(actor, id, version, selectedScope());
            AiResult fixture = new AiResult("SUCCESS", "合成历史第 " + (turn + 1) + " 轮：晨星每周三备份，保留七份；轮换限制磁盘占用。", List.of(), UUID.randomUUID().toString(), "fixture-only", 0, true, null);
            version = store.complete(actor, lease, "合成历史第 " + (turn + 1) + " 轮：请解释备份规则。", fixture,
                    List.of(fixtureDependency()), List.of(fixtureReference()), turn == count - 1 ? lastSummary : null).version();
        }
        return version;
    }

    /** 来源均固定为本次文档当前版本，不允许测试客户端伪造来源。 */
    private static SourceDependency fixtureDependency() { return new SourceDependency(baseId, documentId, 1); }

    /** 只引用本次原文的真实 UTF-16 范围和正式入库处理代次。 */
    private static SessionSource fixtureReference() { return new SessionSource(fixtureDependency(), 1L, null, 0, FIXTURE_TEXT.length()); }

    /** 本次额外管理员用于最后管理员保护；角色降级后旧私人摘要和跨库历史必须重新授权。 */
    private static void testRoleDowngrade() throws Exception {
        check("http_admin_downgrade_restricts_cross_owner_history_and_summary", () -> {
            AccountApplicationService accounts = context.getBean(AccountApplicationService.class);
            UserContext admin = accounts.authenticate(adminToken), protector = accounts.authenticate(login(3));
            SessionStorePort store = context.getBean(SessionStorePort.class);
            long id = store.create(admin, "合成跨库私有会话", "admin-synthetic").id();
            long version = seedTurns(admin, id, 3, new SessionSummary("合成摘要仅用于角色复核断言", 2, List.of(fixtureDependency())));
            accounts.update(protector, admin.userId(), true, UserContext.Role.USER);
            expect(401, request("GET", "/sessions/" + id, adminToken, null, null));
            adminToken = login(2);
            JsonNode history = messages(adminToken, id);
            require(history.size() == 6 && history.toString().indexOf("晨星") < 0 && history.toString().indexOf("每周三") < 0, "downgraded admin retained foreign history text");
            require(history.get(0).path("status").asText().equals("RESTRICTED"), "downgraded history not marked restricted");
            UserContext downgraded = accounts.authenticate(adminToken);
            SessionLease lease = store.begin(downgraded, id, version, ScopeRequest.self());
            try {
                var loaded = context.getBean(SessionHistoryService.class).load(downgraded, lease, new ExecutionBudget(Duration.ofSeconds(60), 10));
                require(loaded.messages().isEmpty() && loaded.summary() == null, "downgraded admin retained illegal model context");
            } finally { store.abort(downgraded, lease); }
        });
    }

    /** 本机提供方固定截断／超时故障用于正式 HTTP 失败路径，单独标明并非真实供应商故障。 */
    private static void testLocalProtocolFailures() throws Exception {
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean disconnectMode = new AtomicBoolean(false);
        CountDownLatch providerEntered = new CountDownLatch(1), providerRelease = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                if (disconnectMode.get()) {
                    providerEntered.countDown();
                    try { providerRelease.await(10, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                } else if (timedOut.get()) {
                    // 独立本机 stub 延迟只用于确定性超时，不对真实模型发送付费慢请求。
                    try { new CountDownLatch(1).await(4, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                String content = disconnectMode.get() ? "合成资料要求每周三备份，保留七份 [E1]。" : "禁止保存的合成截断草稿";
                String finish = disconnectMode.get() ? "stop" : "length";
                byte[] response = ("{\"id\":\"s01-local\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"s01-local\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"" + finish + "\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (java.io.IOException ignored) {
                // 超时客户端断开是故障用例本身，不打印请求头或供应商正文。
            } finally { exchange.close(); }
        });
        server.start();
        defaults.put("S01_LOCAL_MODEL_KEY", "synthetic-local-only-key");
        try {
            closeApplication();
            startApplication("--lab.model.models.primary.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "--lab.model.models.primary.credential-ref=S01_LOCAL_MODEL_KEY", "--lab.model.models.primary.timeout-seconds=1");
            check("local_protocol_truncated_http_failure_no_fake_success", () -> {
                long id = createSession("截断故障");
                HttpResponse<String> response = request("POST", "/chat", ownerToken, chatBody("请解释合成资料的备份规则", selectedScope(), id, 1), null);
                expect(400, response); require(code(response).equals("MODEL_INVALID_OUTPUT"), "truncated model error not explicit");
                require(messages(ownerToken, id).isEmpty(), "truncated draft saved as successful answer");
                require(expect(200, request("GET", "/sessions/" + id, ownerToken, null, null)).path("version").asLong() == 2, "failed execution not released");
                chat("统计我的文档", selectedScope(), id, 2);
            });
            check("local_protocol_sse_failure_error_eof_without_done", () -> {
                long id = createSession("SSE失败");
                HttpResponse<String> response = request("POST", "/chat/stream", ownerToken, chatBody("请解释合成资料的备份规则", selectedScope(), id, 1), null);
                require(response.statusCode() == 200 && response.body().contains("event:error") && !response.body().contains("event:done"), "failed SSE emitted false success or incomplete error");
                require(messages(ownerToken, id).isEmpty(), "failed SSE saved a draft");
            });
            timedOut.set(true);
            check("local_protocol_timeout_releases_session_for_next_request", () -> {
                long id = createSession("超时故障");
                HttpResponse<String> response = request("POST", "/chat", ownerToken, chatBody("请解释合成资料的备份规则", selectedScope(), id, 1), null);
                require(response.statusCode() == 503 || response.statusCode() == 504, "timeout not reported as bounded provider failure");
                require(messages(ownerToken, id).isEmpty(), "timeout saved false successful answer");
                long version = expect(200, request("GET", "/sessions/" + id, ownerToken, null, null)).path("version").asLong();
                require(version == 2, "timeout left live execution"); chat("统计我的文档", selectedScope(), id, version);
            });
            closeApplication(); disconnectMode.set(true);
            startApplication("--lab.model.models.primary.endpoint=http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                    "--lab.model.models.primary.credential-ref=S01_LOCAL_MODEL_KEY", "--lab.model.models.primary.timeout-seconds=8");
            check("real_tcp_sse_disconnect_before_model_return_no_pair", () -> {
                long id = createSession("真实TCP断线");
                URI address = URI.create(origin);
                try (Socket socket = new Socket(address.getHost(), address.getPort())) {
                    socket.setSoTimeout(10000);
                    byte[] body = JSON.writeValueAsBytes(chatBody("请解释合成资料的备份规则", selectedScope(), id, 1));
                    String headers = "POST /api/v1/chat/stream HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + ownerToken + "\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().write(body); socket.getOutputStream().flush();
                    var received = new StringBuilder(); byte[] part = new byte[1024];
                    while (received.indexOf("event:progress") < 0 && received.length() < 8192) {
                        int count = socket.getInputStream().read(part); if (count < 0) break;
                        received.append(new String(part, 0, count, StandardCharsets.UTF_8));
                    }
                    require(received.indexOf("event:progress") >= 0, "no pre-model progress received over TCP");
                    require(providerEntered.await(10, TimeUnit.SECONDS), "model stub not blocked before disconnect");
                    // 收到真实 SSE 后用 TCP RST 关闭，只取消本次连接；两个心跳有机会观测断线。
                    socket.setSoLinger(true, 0); socket.close();
                    new CountDownLatch(1).await(5, TimeUnit.SECONDS); providerRelease.countDown();
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (jdbc.queryForObject("SELECT execution_id IS NOT NULL FROM sessions WHERE id=?", Boolean.class, id) && System.nanoTime() < until) new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
                    require(messages(ownerToken, id).isEmpty(), "disconnected request saved a success pair");
                    require(jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id=? AND execution_id IS NULL AND version=2", Integer.class, id) == 1, "disconnected request lease not released");
                    chat("统计我的文档", selectedScope(), id, 2);
                    META.put("tcp_disconnect_evidence", "formal HTTP received progress then TCP RST while local provider blocked; two heartbeat periods; no successful pair");
                } finally { providerRelease.countDown(); }
            });
            META.put("local_protocol_faults", "truncation/SSE error/timeout; real provider faults not simulated as passed");
        } finally {
            closeApplication(); server.stop(0); executor.shutdownNow(); defaults.remove("S01_LOCAL_MODEL_KEY"); startApplication();
        }
    }

    /** 禁用来源后 HTTP 不交付旧正文；删除本人会话后不再读历史或重用原版本。 */
    private static void testRevocationAndDeletion() throws Exception {
        check("http_disabled_source_history_redacted_and_no_model_publish", () -> {
            long version = jdbc.queryForObject("SELECT version FROM knowledge_bases WHERE id=?", Long.class, baseId);
            context.getBean(KnowledgeBaseApplicationService.class).update(owner, baseId, version, "S01 synthetic", "", false);
            JsonNode history = messages(ownerToken, sessionId);
            require(history.toString().indexOf("S01CTX") < 0 && history.toString().indexOf("每周三") < 0, "revoked source history text leaked");
            int denied = request("POST", "/chat", ownerToken, chatBody("刚才合成资料的备份规则是什么", selectedScope(), sessionId, sessionVersion), null).statusCode();
            require(denied == 403 || denied == 409, "disabled selected source allowed chat");
        });
        check("http_source_delete_redacts_history_and_filters_model_context", () -> {
            long version = jdbc.queryForObject("SELECT version FROM knowledge_bases WHERE id=?", Long.class, baseId);
            context.getBean(KnowledgeBaseApplicationService.class).update(owner, baseId, version, "S01 synthetic", "", true);
            long id = createSession("来源删除"), sessionVersion = seedTurns(owner, id, 1, null);
            expect(200, request("DELETE", "/documents/" + documentId + "?documentVersion=1", ownerToken, null, null));
            JsonNode history = messages(ownerToken, id);
            require(history.size() == 2 && history.get(0).path("status").asText().equals("RESTRICTED") && history.toString().indexOf("晨星") < 0, "deleted source history leaked");
            SessionStorePort store = context.getBean(SessionStorePort.class);
            SessionLease lease = store.begin(owner, id, sessionVersion, selectedScope());
            try {
                var loaded = context.getBean(SessionHistoryService.class).load(owner, lease, new ExecutionBudget(Duration.ofSeconds(60), 10));
                require(loaded.messages().isEmpty() && loaded.summary() == null && loaded.dependencies().isEmpty(), "deleted source entered model context");
            } finally { store.abort(owner, lease); }
        });
        check("http_versioned_delete_erases_message_body", () -> {
            sessionVersion = expect(200, request("GET", "/sessions/" + sessionId, ownerToken, null, null)).path("version").asLong();
            expect(204, request("DELETE", "/sessions/" + sessionId + "?version=" + sessionVersion, ownerToken, null, null));
            expect(403, request("GET", "/sessions/" + sessionId + "/messages", ownerToken, null, null));
            require(jdbc.queryForObject("SELECT COUNT(*) FROM messages WHERE session_id=? AND content<>''", Integer.class, sessionId) == 0, "deleted message body retained");
        });
    }

    /** helper 只复用正式会话创建协议，随机键避免跨用例误共享。 */
    private static long createSession(String title) throws Exception { return expect(201, request("POST", "/sessions", ownerToken, Map.of("title", title), UUID.randomUUID().toString())).path("id").asLong(); }

    /** 明确只选择本次合成库；测试账号不请求 ALL。 */
    private static ScopeRequest selectedScope() { return new ScopeRequest(ScopeRequest.Mode.SELECTED, List.of(baseId), null); }

    /** 保持与公开 JSON 一致，第二轮不通过测试专用参数携带历史。 */
    private static Map<String, Object> chatBody(String question, ScopeRequest scope, long id, long version) { return Map.of("question", question, "scope", scope, "sessionId", id, "sessionVersion", version); }

    /** 完整 HTTP 两轮走真实认证、授权、事务、AI 及最终序列化。 */
    private static JsonNode chat(String question, ScopeRequest scope, long id, long version) throws Exception { return expect(200, request("POST", "/chat", ownerToken, chatBody(question, scope, id, version), null)); }

    /** 历史分页由正式业务层复核来源，不能绕过为原始 SQL 正文探针。 */
    private static JsonNode messages(String token, long id) throws Exception { return expect(200, request("GET", "/sessions/" + id + "/messages?afterSeq=0&size=20", token, null, null)); }

    /** 令牌只进入请求头；请求／响应正文绝不写日志或证据。 */
    private static HttpResponse<String> request(String method, String path, String token, Object body, String key) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(origin + "/api/v1" + path)).timeout(Duration.ofSeconds(75));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json; charset=UTF-8");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8));
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 仅记录状态码与脱敏错误码，不将底层服务错误正文泄漏到输出。 */
    private static JsonNode expect(int status, HttpResponse<String> response) throws Exception {
        require(response.statusCode() == status, "expected HTTP " + status + "; actual HTTP " + response.statusCode() + "; code=" + code(response));
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    /** 响应正文可能包含用户资料，只从固定错误 DTO 提取短错误码。 */
    private static String code(HttpResponse<String> response) { try { return JSON.readTree(response.body()).path("code").asText("none"); } catch (Exception ignored) { return "non-JSON"; } }

    /** JDBC 设置有界连接时间，连接参数与凭证仅存内存。 */
    private static Connection connect(String url) throws SQLException {
        Properties properties = new Properties(); properties.setProperty("user", configuration("DB_USERNAME")); properties.setProperty("password", configuration("DB_PASSWORD"));
        properties.setProperty("connectTimeout", "5000"); properties.setProperty("socketTimeout", "15000");
        return DriverManager.getConnection(url, properties);
    }

    /** 关闭本探针自己的上下文，不接触 IDEA 或其他服务进程。 */
    private static void closeApplication() { if (context != null) { try { context.close(); } catch (Exception ignored) {} context = null; } }

    /** 自己创建的 schema 才可 DROP；回退库只按本次确切用户 ID 删除，保留其余用户数据及迁移。 */
    private static void cleanupFixtures() {
        try {
            if (indexCreated && es != null) { es.indices().delete(r -> r.index(TEST_INDEX)); META.put("temporary_index_removed", true); }
        } catch (Throwable error) {
            RESULTS.add(Map.of("case", "fixture_index_cleanup", "status", "FAIL", "reason", safeReason(error)));
            META.put("cleanup_incomplete", true);
        } finally {
            if (esTransport != null) try { esTransport.close(); } catch (Exception ignored) {}
        }
        // 搜索服务清理失败也继续清理自己的数据库资料，避免一处远程故障阻止独立收尾。
        try {
            if (schemaCreated) {
                require(TEST_SCHEMA.matches("novid_s01_[a-f0-9]{12}") && !URI.create(configuredUrl.substring(5)).getPath().equals("/" + TEST_SCHEMA), "unsafe schema cleanup");
                try (Connection connection = connect(configuredUrl); Statement statement = connection.createStatement()) { statement.executeUpdate("DROP DATABASE `" + TEST_SCHEMA + "`"); }
                META.put("temporary_schema_removed", true);
            } else if (!FIXTURE_USERS.isEmpty()) {
                DriverManagerDataSource source = new DriverManagerDataSource(testedUrl, configuration("DB_USERNAME"), configuration("DB_PASSWORD"));
                JdbcTemplate cleanup = new JdbcTemplate(source); NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(cleanup);
                new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status -> {
                    Map<String, Object> users = Map.of("users", FIXTURE_USERS);
                    named.update("DELETE FROM messages WHERE session_id IN (SELECT id FROM sessions WHERE user_id IN (:users))", users);
                    named.update("DELETE FROM sessions WHERE user_id IN (:users)", users);
                    for (String table : List.of("ai_runs", "knowledge_access_audit", "request_deduplications")) named.update("DELETE FROM " + table + " WHERE actor_user_id IN (:users)", users);
                    for (String table : List.of("auth_tokens", "profile_memories")) named.update("DELETE FROM " + table + " WHERE user_id IN (:users)", users);
                    Map<String, Object> docs = Map.of("id", documentId);
                    // S03的外键清理只按本次明确document ID，保留其他资料和所有旧迁移。
                    for(String table:List.of("ingestion_model_attempts","ingestion_batches")) named.update("DELETE FROM "+table+" WHERE ingestion_id IN (SELECT id FROM document_ingestions WHERE document_id=:id)",docs);
                    for (String table : List.of("source_dependencies", "chunks", "context_parents", "document_sections", "document_ingestions", "document_versions")) named.update("DELETE FROM " + table + " WHERE document_id=:id", docs);
                    named.update("DELETE FROM outbox_events WHERE event_type IN ('INGEST_DOCUMENT','DELETE_DOCUMENT','PRUNE_DOCUMENT') AND resource_id=:id", docs);
                    named.update("DELETE FROM documents WHERE owner_user_id IN (:users)", users);
                    named.update("DELETE FROM outbox_events WHERE event_type='DELETE_BASE' AND resource_id=:id", Map.of("id", baseId));
                    named.update("DELETE FROM knowledge_bases WHERE owner_user_id IN (:users)", users);
                    named.update("DELETE FROM users WHERE id IN (:users)", users);
                });
                require(named.queryForObject("SELECT COUNT(*) FROM users WHERE id IN (:users)", Map.of("users", FIXTURE_USERS), Integer.class) == 0, "synthetic users retained");
                META.put("exact_fixture_ids_removed", true);
                for (var existing : EXISTING_IDS.entrySet()) {
                    if (existing.getValue().isEmpty()) continue;
                    require(named.queryForObject("SELECT COUNT(*) FROM " + existing.getKey() + " WHERE id IN (:ids)", Map.of("ids", existing.getValue()), Integer.class) == existing.getValue().size(), "existing row disappeared during validation");
                }
                META.put("existing_users_documents_tasks_preferences_retained", true);
            }
        } catch (Throwable error) { RESULTS.add(Map.of("case", "fixture_database_cleanup", "status", "FAIL", "reason", safeReason(error))); META.put("cleanup_incomplete", true); }
    }

    /** 每项结果只输出稳定名字与通过／失败，不回显资料、地址和凭证。 */
    private static void check(String name, Checked action) {
        try { action.run(); RESULTS.add(Map.of("case", name, "status", "PASS")); }
        catch (Throwable error) { RESULTS.add(Map.of("case", name, "status", "FAIL", "reason", safeReason(error))); }
    }

    /** 固定程序断言文本可写证据；供应商或 JDBC 异常仅写类型与错误码。 */
    private static String safeReason(Throwable error) {
        if (error instanceof AssertionError) return Objects.toString(error.getMessage(), "assertion failed");
        if (error instanceof com.example.ailab.contract.error.LabException lab) return "LabException:" + lab.code();
        if (error instanceof SQLException sql) return "SQLException:" + sql.getErrorCode();
        var chain = new ArrayList<String>();
        for (Throwable cause = error; cause != null && chain.size() < 8; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName());
            // 仅白名单中的项目自有固定注册错误可打印；其余异常消息可能带地址或密钥，仍拒绝输出。
            if (cause instanceof IllegalArgumentException && cause.getMessage() != null
                    && (cause.getMessage().startsWith("真实模型缺名称、地址或环境凭证")
                    || cause.getMessage().equals("模型窗口或超时配置不合法")
                    || cause.getMessage().equals("两个别名不能作为两个实际主备目标"))) return String.join(" -> ", chain) + ":" + cause.getMessage();
        }
        return String.join(" -> ", chain);
    }

    /** 失败立即停止当前用例，最终非零退出，不把跳过或有异常的检查计通过。 */
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
