import com.example.ailab.app.LabApplication;
import com.example.ailab.business.application.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.TaskStorePort;
import com.example.ailab.data.repository.SqlSupport;
import org.springframework.boot.SpringApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 真实 MySQL 短事务内验证合成任务后回滚，不调用外部模型，不要求建库权限。 */
public class TaskProgressRollbackValidation {
    private static int passed;
    private static final String PASSWORD = "progress-validation-password";

    /** 正式启动应用迁移 V4；后台队列关闭，合成资料最终全部回滚。 */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try (var context = SpringApplication.run(LabApplication.class,
                "--lab.search.enabled=false", "--lab.bootstrap.enabled=false", "--lab.task.worker-enabled=false",
                "--lab.ingestion.worker-enabled=false", "--lab.model.mode=mock",
                "--spring.main.web-application-type=none", "--spring.main.banner-mode=off", "--logging.level.root=OFF")) {
            var jdbc = context.getBean(JdbcTemplate.class);
            // 回填只核对元数据，不通过 API 读取任何已有用户的私人任务内容。
            check(jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks t WHERE (SELECT COUNT(*) FROM task_step_progress p WHERE p.task_id=t.id)<>5", Long.class) == 0, "existing_tasks_have_five_steps");
            long usersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
            long tasksBefore = jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks", Long.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            transaction.executeWithoutResult(status -> {
                try {
                    String username = "progress-test-" + UUID.randomUUID().toString().substring(0, 12);
                    context.getBean(SqlSupport.class).insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,?,'ADMIN',FALSE)", username, new BCryptPasswordEncoder(12).encode(PASSWORD));
                    var accounts = context.getBean(AccountApplicationService.class);
                    var actor = accounts.authenticate(accounts.login(username, PASSWORD).token());
                    var tasks = context.getBean(TaskStorePort.class);
                    var application = context.getBean(TaskApplicationService.class);
                    var base = context.getBean(KnowledgeBaseApplicationService.class).create(actor, "合成进度验证", "");
                    var document = context.getBean(DocumentApplicationService.class).upload(actor, base.id(), "progress.txt", "text/plain",
                            "合成测试资料，不含真实用户内容。".getBytes(StandardCharsets.UTF_8), "progress-document");
                    var request = new TaskRequest("FAQ", "合成进度验证", ScopeRequest.self(), List.of(document.id()), "progress-task");
                    var queued = application.create(actor, request);
                    check(queued.progress().stage().equals("WAITING_FOR_WORKER") && queued.progress().percent() == 0, "disabled_worker_message");
                    check(application.create(actor, request).taskId() == queued.taskId(), "idempotent_creation");
                    var lease = fixtureLease(jdbc, tasks, actor, queued, request);
                    check(tasks.read(actor, queued.taskId()).progress().executionActive() && lease.task().progress().lastHeartbeatAt() != null, "lease_and_heartbeat");
                    tasks.beginStep(lease, "prepare"); tasks.completePreparation(lease);
                    tasks.beginStep(lease, "research"); tasks.beginStep(lease, "analysis");
                    check(tasks.read(actor, queued.taskId()).progress().currentSteps().equals(List.of("research", "analysis")), "parallel_steps_visible");
                    var sources = List.of(new SourceDependency(base.id(), document.id(), 1));
                    tasks.checkpoint(lease, new TaskCheckpoint("research", "合成要点", sources, false));
                    check(tasks.read(actor, queued.taskId()).progress().percent() == 40, "completed_checkpoint_progress");
                    tasks.action(actor, queued.taskId(), "pause");
                    check(tasks.read(actor, queued.taskId()).progress().stage().equals("PAUSED"), "paused_progress");
                    var stale = lease;
                    denied("STALE_EXECUTION", () -> tasks.beginStep(stale, "report"));
                    tasks.action(actor, queued.taskId(), "resume"); lease = fixtureLease(jdbc, tasks, actor, queued, request);
                    tasks.beginStep(lease, "research");
                    check(tasks.read(actor, queued.taskId()).progress().steps().get(1).status().equals("SUCCEEDED"), "resume_preserves_success");
                    tasks.beginStep(lease, "analysis"); tasks.checkpoint(lease, new TaskCheckpoint("analysis", "合成统计", sources, false));
                    tasks.beginStep(lease, "report"); var report = new TaskCheckpoint("report", "合成报告", sources, false);
                    tasks.checkpoint(lease, report); tasks.beginStep(lease, "publish");
                    check(tasks.read(actor, queued.taskId()).progress().percent() == 80, "publication_required_for_completion");
                    tasks.publish(lease, report);
                    var finished = tasks.read(actor, queued.taskId());
                    check(finished.progress().percent() == 100 && finished.artifactId() != null && finished.progress().pollAfterMillis() == 0, "published_terminal_progress");
                    String observerName = "observer-" + UUID.randomUUID().toString().substring(0, 12);
                    var user = accounts.create(actor, observerName);
                    var temporary = accounts.authenticate(accounts.login(observerName, user.temporaryPassword()).token());
                    accounts.changePassword(temporary, user.temporaryPassword(), PASSWORD);
                    accounts.update(actor, temporary.userId(), true, UserContext.Role.ADMIN);
                    var observer = accounts.authenticate(accounts.login(observerName, PASSWORD).token());
                    denied("ACCESS_DENIED", () -> tasks.read(observer, queued.taskId()));
                    var failureRequest = new TaskRequest("FAQ", "失败验证", ScopeRequest.self(), List.of(document.id()), "progress-failure");
                    var failing = application.create(actor, failureRequest);
                    var failingLease = fixtureLease(jdbc, tasks, actor, failing, failureRequest);
                    tasks.beginStep(failingLease, "prepare"); tasks.completePreparation(failingLease); tasks.beginStep(failingLease, "research");
                    tasks.fail(failingLease, "MODEL_TIMEOUT");
                    var failed = tasks.read(actor, failing.taskId());
                    check(failed.progress().percent() == 20 && failed.progress().steps().get(1).errorCode().equals("MODEL_TIMEOUT"), "failed_step_visible");
                    var recoveryRequest = new TaskRequest("FAQ", "租约验证", ScopeRequest.self(), List.of(document.id()), "progress-expired");
                    var recovering = application.create(actor, recoveryRequest);
                    var expired = fixtureLease(jdbc, tasks, actor, recovering, recoveryRequest); tasks.beginStep(expired, "prepare");
                    jdbc.update("UPDATE ai_tasks SET lease_until=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE id=?", recovering.taskId());
                    check(tasks.read(actor, recovering.taskId()).progress().stage().equals("WAITING_FOR_RECOVERY"), "expired_lease_visible");
                    fixtureLease(jdbc, tasks, actor, recovering, recoveryRequest);
                    denied("STALE_EXECUTION", () -> tasks.beginStep(expired, "prepare"));
                    // 模拟自动终止遗留 claimed_at 的行，验证停止后不会继续增加执行秒数。
                    jdbc.update("UPDATE ai_tasks SET status='FAILED',error_code='BUDGET_EXCEEDED',claimed_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 5 SECOND),lease_until=NULL WHERE id=?", recovering.taskId());
                    long frozen = tasks.read(actor, recovering.taskId()).progress().elapsedExecutionSeconds();
                    jdbc.update("UPDATE ai_tasks SET updated_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 2 SECOND),claimed_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 7 SECOND) WHERE id=?", recovering.taskId());
                    check(tasks.read(actor, recovering.taskId()).progress().elapsedExecutionSeconds() == frozen, "stopped_execution_time_frozen");
                    fixtureLease(jdbc, tasks, actor, recovering, recoveryRequest);
                    var cancelled = tasks.action(actor, recovering.taskId(), "cancel");
                    check(cancelled.progress().stage().equals("CANCELLED") && !cancelled.progress().executionActive(), "cancelled_progress");
                } finally { status.setRollbackOnly(); }
            });
            check(jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class) == usersBefore
                    && jdbc.queryForObject("SELECT COUNT(*) FROM ai_tasks", Long.class) == tasksBefore, "fixtures_rolled_back");
        }
        System.out.println("{\"task_progress_validation\":\"PASS\",\"checks\":" + passed
                + ",\"external_model_calls\":0,\"fixture_lease_simulated\":true,\"fixtures_rolled_back\":true}");
    }

    /** 仅针对当前事务新建的合成任务模拟租约，不领取或修改已有用户的任务。 */
    private static TaskLease fixtureLease(JdbcTemplate jdbc, TaskStorePort tasks, UserContext actor, TaskSnapshot task, TaskRequest request) {
        jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='progress-fixture',state_version=state_version+1,fencing_token=fencing_token+1,lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND),claimed_at=CURRENT_TIMESTAMP(6),started_at=COALESCE(started_at,CURRENT_TIMESTAMP(6)),heartbeat_at=CURRENT_TIMESTAMP(6) WHERE id=? AND requester_user_id=?", task.taskId(), actor.userId());
        jdbc.update("UPDATE task_step_progress SET status='PENDING',started_at=NULL WHERE task_id=? AND status IN ('RUNNING','PAUSED')", task.taskId());
        long token = jdbc.queryForObject("SELECT fencing_token FROM ai_tasks WHERE id=?", Long.class, task.taskId());
        return new TaskLease(tasks.read(actor, task.taskId()), request, actor, "progress-fixture", token);
    }

    /** 只输出断言名称，不回显正文、地址、凭证或授权令牌。 */
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError("Task progress check failed: " + name);
        passed++;
    }

    /** 旧执行者与其他管理员都不能读取或修改不属于自己的任务事实。 */
    private static void denied(String code, Runnable action) {
        try { action.run(); } catch (LabException error) { check(code.equals(error.code()), "authorization_or_fencing"); return; }
        throw new AssertionError("Expected rejection: " + code);
    }
}
