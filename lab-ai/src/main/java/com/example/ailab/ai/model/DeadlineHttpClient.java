package com.example.ailab.ai.model;

import dev.langchain4j.http.client.*;
import dev.langchain4j.http.client.sse.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** 缓存SDK客户端的同步传输；每次发送用当前线程预算，不缓存请求截止时间。 */
final class DeadlineHttpClient implements dev.langchain4j.http.client.HttpClient {
    record Scope(ExecutionBudget budget, int timeoutSeconds, java.util.concurrent.atomic.AtomicReference<String> responseBody) { }
    static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final java.net.http.HttpClient client;
    DeadlineHttpClient() {
        this(java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
    }
    /** 传输协议测试可替换网络层，实际发送仍走完整响应和预算检查。 */
    DeadlineHttpClient(java.net.http.HttpClient client) { this.client = java.util.Objects.requireNonNull(client); }
    /** 只保留HTTP状态与冷却时间，异常不携带提供方正文、地址或密钥。 */
    static final class Failure extends RuntimeException {
        final int status; final Duration retryAfter;
        /** 错误正文不进入SDK异常链，避免观测或日志外泄。 */
        Failure(int status, Duration retryAfter) { super("受控模型HTTP失败"); this.status = status; this.retryAfter = retryAfter; }
    }
    /** 同步完整响应取目标配置、调用方单次等待上限和任务剩余时间的最小值。 */
    @Override public SuccessfulHttpResponse execute(dev.langchain4j.http.client.HttpRequest request) {
        var scope = CURRENT.get();
        if (scope == null) throw new IllegalStateException("缺少模型调用预算");
        var remaining = scope.budget().timeout();
        var configured = Duration.ofSeconds(scope.timeoutSeconds());
        var timeout = remaining.compareTo(configured) < 0 ? remaining : configured;
        var builder = java.net.http.HttpRequest.newBuilder(URI.create(request.url())).timeout(timeout);
        request.headers().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        builder.method(request.method().name(), java.net.http.HttpRequest.BodyPublishers.ofString(request.body()));
        try {
            var response = com.example.ailab.contract.http.HttpRequests.send(client, builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new Failure(response.statusCode(), retryAfter(response.headers().firstValue("Retry-After").orElse(null)));
            scope.budget().check();
            scope.responseBody().set(response.body());
            return SuccessfulHttpResponse.builder().statusCode(response.statusCode()).headers(response.headers().map()).body(response.body()).build();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new com.example.ailab.contract.error.LabException("REQUEST_CANCELLED", "模型等待已中断");
        } catch (java.io.IOException error) { throw new RuntimeException("受控模型传输失败", error); }
    }
    /** 支持秒和HTTP日期；不等待限流窗口，也不将超长Retry-After缩短以绕过额度。 */
    private Duration retryAfter(String value) {
        if (value == null) return null;
        try {
            Duration delay = value.matches("[0-9]+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException invalid) { return null; }
    }
    /** 现有SSE是全文校验后推送，不能意外启用SDK原生流式并拼接备用答案。 */
    @Override public void execute(dev.langchain4j.http.client.HttpRequest request, ServerSentEventParser parser, ServerSentEventListener listener) {
        throw new UnsupportedOperationException("模型原生流式尚未启用");
    }
    /** SDK只在构建时读取默认超时；真正发送仍使用当前请求预算。 */
    static final class Builder implements HttpClientBuilder {
        private Duration connect = Duration.ofSeconds(30), read = Duration.ofSeconds(30);
        private final DeadlineHttpClient client;
        /** chat与embedding复用同一安全连接池。 */
        Builder(DeadlineHttpClient client) { this.client = client; }
        /** SDK默认连接超时仅作元数据。 */
        public Duration connectTimeout() { return connect; }
        /** 不保存请求级时间。 */
        public HttpClientBuilder connectTimeout(Duration value) { connect = value; return this; }
        /** 默认读取上限。 */
        public Duration readTimeout() { return read; }
        /** 不覆盖动态发送预算。 */
        public HttpClientBuilder readTimeout(Duration value) { read = value; return this; }
        /** 固定连接池只创建一次。 */
        public dev.langchain4j.http.client.HttpClient build() { return client; }
    }
}
