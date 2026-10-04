package com.example.ailab.app;

import com.example.ailab.contract.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** OTLP/HTTP JSON适配只发送逻辑元数据，默认关闭，不上传用户／正文／工具结果。 */
public final class OtlpTraceExporter {
    private final boolean enabled;
    private final URI endpoint;
    private final String authorization;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ObjectMapper json=new ObjectMapper();
    /** endpoint必须显式为接收traces的完整URL，认证头不进入任何诊断输出。 */
    public OtlpTraceExporter(boolean enabled,String endpoint,String authorization) {
        this.enabled=enabled; this.authorization=authorization;
        try { this.endpoint=enabled?URI.create(endpoint):null; }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("OTLP HTTP配置无效"); }
        if(enabled && (this.endpoint.getHost()==null || this.endpoint.getUserInfo()!=null || this.endpoint.getQuery()!=null
                || !Set.of("https","http").contains(this.endpoint.getScheme()) || !this.endpoint.getPath().endsWith("/v1/traces")))
            throw new IllegalArgumentException("OTLP HTTP配置无效");
    }
    /** HTTP成功还核对partialSuccess，未知或部分接收如实算观测失败；不重试远程导出。 */
    public boolean export(TraceSnapshot run,List<TraceNode> nodes) {
        if(!enabled) return true;
        try {
            var spans=new ArrayList<Map<String,Object>>();
            for(var n:nodes) {
                var span=new LinkedHashMap<String,Object>();
                span.put("traceId",run.traceId().replace("-","")); span.put("spanId",spanId(n.spanId()));
                if(n.parentSpanId()!=null) span.put("parentSpanId",spanId(n.parentSpanId()));
                span.put("name",n.name()); span.put("kind",1);
                span.put("startTimeUnixNano",nano(n.startedAt())); span.put("endTimeUnixNano",nano(n.endedAt()==null?n.startedAt():n.endedAt()));
                span.put("status",Map.of("code",Set.of("SUCCESS","REUSED").contains(n.status())?1:n.status().equals("RUNNING")?0:2));
                var attrs=new ArrayList<Map<String,Object>>();
                attrs.add(attribute("node.type",n.type()));
                if(n.agentId()!=null) attrs.add(attribute("agent.id",n.agentId()));
                if(n.modelId()!=null) attrs.add(attribute("model.id",n.modelId()));
                if(n.errorCode()!=null) attrs.add(attribute("error.code",n.errorCode()));
                span.put("attributes",attrs); spans.add(span);
            }
            var body=Map.of("resourceSpans",List.of(Map.of("resource",Map.of("attributes",List.of(attribute("service.name","novid"))),
                    "scopeSpans",List.of(Map.of("scope",Map.of("name","novid-s06"),"spans",spans)))));
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(2)).header("Content-Type","application/json");
            if(!authorization.isBlank()) request.header("Authorization",authorization);
            var pending=client.sendAsync(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),info->new LimitedBody());
            HttpResponse<byte[]> response;
            // 整个确认包括慢响应正文至多2秒；超时取消本地订阅，不无限占用观测线程。
            try { response=pending.get(2,java.util.concurrent.TimeUnit.SECONDS); }
            catch(Exception unavailable) { pending.cancel(true); return false; }
            String responseBody=new String(response.body(),java.nio.charset.StandardCharsets.UTF_8);
            if(response.statusCode()!=200) return false;
            if(responseBody.isBlank()) return true;
            var partial=json.readTree(responseBody).path("partialSuccess");
            return partial.path("rejectedSpans").asLong(0)==0 && partial.path("errorMessage").asText("").isBlank();
        } catch(Exception unavailable) { return false; }
    }
    /** 确认正文边接收边限制到4KB，超大或慢响应不能耗尽内存／无限等待。 */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private java.util.concurrent.Flow.Subscription subscription;
        /** HTTP客户端只等待有界结果，不暴露响应原文。 */
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        /** 顺序接收，每批次必须先过大小检查。 */
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { this.subscription=subscription; subscription.request(1); }
        /** 超过4KB立即取消，不缓存其余正文。 */
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for(var buffer:buffers) {
                if(bytes.size()+buffer.remaining()>4096) {subscription.cancel();result.completeExceptionally(new IllegalStateException("观测确认超限"));return;}
                var data=new byte[buffer.remaining()];buffer.get(data);bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        /** 外部异常只传递到固定失败结果，调用方不记录响应消息。 */
        public void onError(Throwable error) { result.completeExceptionally(error); }
        /** 合法完整确认才能核验partialSuccess。 */
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    /** OTLP JSON使用字符串整数避免纳秒精度损失。 */
    private String nano(java.time.Instant instant) { return Long.toString(instant.getEpochSecond()*1_000_000_000L+instant.getNano()); }
    /** UUID取稳定64位span标识，原业务标识不发送外部。 */
    private String spanId(String id) { return id.replace("-","").substring(0,16); }
    /** 白名单字符串属性不会接受业务正文或用户身份。 */
    private Map<String,Object> attribute(String key,String value) { return Map.of("key",key,"value",Map.of("stringValue",value)); }
}
