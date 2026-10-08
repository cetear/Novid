package com.example.ailab.ai.model;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/**
 * 在接收阶段有界缓冲响应，超限取消订阅，避免提供方无界正文占满内存。
 */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final int maximum;
    private final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
    private final CompletableFuture<byte[]> result = new CompletableFuture<>();
    private Flow.Subscription subscription;

    /**
     * 协议仅需要有限JSON正文，媒体二进制由单独存储入口处理。
     */
    LimitedBodySubscriber(int maximum) {
        this.maximum = maximum;
    }

    /**
     * 完成时只公开字节，不保留认证或连接对象。
     */
    public CompletionStage<byte[]> getBody() {
        return result;
    }

    /**
     * 逐批消费，不请求无限缓冲。
     */
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1);
    }

    /**
     * 超限立即取消，异常不回显响应正文。
     */
    public void onNext(List<ByteBuffer> items) {
        for (var item : items) {
            if (buffer.size() + item.remaining() > maximum) {
                subscription.cancel();
                result.completeExceptionally(new IllegalStateException("媒体响应超限"));
                return;
            }
            byte[] bytes = new byte[item.remaining()];
            item.get(bytes);
            buffer.writeBytes(bytes);
        }
        subscription.request(1);
    }

    /**
     * 失败交给稳定错误分类，不能自动重发生成。
     */
    public void onError(Throwable error) {
        result.completeExceptionally(error);
    }

    /**
     * 正常结束才释放完整有界正文。
     */
    public void onComplete() {
        result.complete(buffer.toByteArray());
    }
}
