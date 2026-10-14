package com.example.ailab.data.config;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.slf4j.LoggerFactory;

import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 应用侧语句耗时包含查询结果读取／映射，不包含取连接、参数绑定和事务提交。 */
@Intercepts({
        @Signature(type = StatementHandler.class, method = "query", args = {Statement.class, ResultHandler.class}),
        @Signature(type = StatementHandler.class, method = "update", args = {Statement.class})
})
public final class SlowSqlInterceptor implements Interceptor {
    public static final String LOGGER_NAME = "com.example.ailab.sql.slow";
    private final long thresholdNanos;
    private final long thresholdMillis;
    private final int maxSqlLength;
    private final LongSupplier clock;
    private final Consumer<Event> recorder;

    public SlowSqlInterceptor(long thresholdMillis, int maxSqlLength) {
        this(thresholdMillis, maxSqlLength, System::nanoTime, SlowSqlInterceptor::log);
    }

    SlowSqlInterceptor(long thresholdMillis, int maxSqlLength, LongSupplier clock, Consumer<Event> recorder) {
        if (thresholdMillis < 1 || thresholdMillis > 600000 || maxSqlLength < 128 || maxSqlLength > 65536)
            throw new IllegalArgumentException("慢 SQL 配置范围无效");
        this.thresholdMillis = thresholdMillis;
        this.thresholdNanos = TimeUnit.MILLISECONDS.toNanos(thresholdMillis);
        this.maxSqlLength = maxSqlLength;
        this.clock = clock;
        this.recorder = recorder;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long started = clock.getAsLong();
        Throwable failure = null;
        try {
            return invocation.proceed();
        } catch (Throwable error) {
            failure = org.apache.ibatis.reflection.ExceptionUtil.unwrapThrowable(error);
            throw error;
        } finally {
            // 诊断错误不得覆盖原返回值／异常；不输出原始异常消息或绑定参数。
            try {
                long elapsed = clock.getAsLong() - started;
                if (elapsed >= thresholdNanos) record(invocation, elapsed, failure);
            } catch (RuntimeException ignored) {
                // 文件写入与诊断失败由 Log4j 状态日志处理，不影响数据库调用。
            }
        }
    }

    private void record(Invocation invocation, long elapsed, Throwable failure) {
        Object target = invocation.getTarget();
        while (java.lang.reflect.Proxy.isProxyClass(target.getClass())
                && java.lang.reflect.Proxy.getInvocationHandler(target) instanceof Plugin plugin) {
            target = SystemMetaObject.forObject(plugin).getValue("target");
        }
        var meta = SystemMetaObject.forObject(target);
        String path = meta.hasGetter("delegate.mappedStatement") ? "delegate.mappedStatement" : "mappedStatement";
        var statement = (MappedStatement) meta.getValue(path);
        String safeSql = SlowSqlTemplate.sanitize(((StatementHandler) target).getBoundSql().getSql());
        boolean omitted = safeSql == null;
        String hash = hash(omitted ? statement.getId() : safeSql);
        boolean truncated = !omitted && safeSql.length() > maxSqlLength;
        String sql = omitted ? "OMITTED" : safeSql.substring(0, Math.min(safeSql.length(), maxSqlLength));
        recorder.accept(new Event(statement.getId(), statement.getSqlCommandType().name(),
                TimeUnit.NANOSECONDS.toMillis(elapsed), thresholdMillis,
                failure == null ? "SUCCESS" : "FAILED", failure == null ? "NONE" : failure.getClass().getSimpleName(),
                hash, truncated, omitted, sql));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static void log(Event event) {
        LoggerFactory.getLogger(LOGGER_NAME).warn(
                "event=db.slow_sql statementId={} sqlType={} durationMs={} thresholdMs={} outcome={} errorType={} sqlHash={} truncated={} omitted={} sql={}",
                event.statementId(), event.sqlType(), event.durationMs(), event.thresholdMs(), event.outcome(),
                event.errorType(), event.sqlHash(), event.truncated(), event.omitted(), event.sql());
    }

    record Event(String statementId, String sqlType, long durationMs, long thresholdMs, String outcome,
                 String errorType, String sqlHash, boolean truncated, boolean omitted, String sql) { }
}
