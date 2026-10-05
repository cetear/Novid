package com.example.ailab.contract.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** 完整响应的总期限；调用方的订阅器必须等待正文结束后才完成。 */
public final class HttpRequests {
    private HttpRequests() { }

    public static <T> HttpResponse<T> send(HttpClient client, HttpRequest request,
                                         HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        var timeout = request.timeout().orElseThrow(() -> new IllegalArgumentException("HTTP请求必须指定总期限"));
        long started = System.nanoTime();
        var response = client.sendAsync(request, handler);
        try {
            long remaining = timeout.toNanos() - (System.nanoTime() - started);
            if (remaining <= 0) throw new TimeoutException();
            return response.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException expired) {
            throw new HttpTimeoutException("HTTP完整响应超时");
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof IOException io) throw io;
            throw new IOException("HTTP传输失败", failed.getCause());
        } finally {
            if (!response.isDone()) response.cancel(true);
        }
    }

    /** 收取阶段限制大小，避免先无界缓冲再检查；正常完成表示正文已收完。 */
    public static HttpResponse.BodyHandler<byte[]> boundedBytes(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException("响应大小上限必须为正数");
        return info -> new HttpResponse.BodySubscriber<>() {
            private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            private final CompletableFuture<byte[]> body = new CompletableFuture<>();
            private Flow.Subscription subscription;
            public CompletionStage<byte[]> getBody() { return body; }
            public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
            public void onNext(List<ByteBuffer> items) {
                for (var item : items) {
                    if (item.remaining() > maximum - buffer.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new IOException("HTTP响应超过大小上限"));
                        return;
                    }
                    byte[] bytes = new byte[item.remaining()];
                    item.get(bytes); buffer.writeBytes(bytes);
                }
                subscription.request(1);
            }
            public void onError(Throwable error) { body.completeExceptionally(error); }
            public void onComplete() { body.complete(buffer.toByteArray()); }
        };
    }
}
