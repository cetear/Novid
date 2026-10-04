package com.example.ailab.web.controller;

import com.example.ailab.business.application.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.context.RequestCancellation;
import com.example.ailab.web.dto.Requests;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;

import java.util.*;
import java.io.IOException;
import java.util.concurrent.ScheduledFuture;

/**
 * 只有通过汇聚的模型内容才进入 JSON/SSE；私人资源无 ADMIN 旁路。
 */
@RestController
@RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantApplicationService assistant;
    private final PersonalApplicationService personal;
    private final ObjectMapper json;
    private final ChatStreamExecutor streams;

    /**
     * 不直接注入模型 SDK 或 AI 实现。
     */
    public AssistantController(AssistantApplicationService a, PersonalApplicationService p, ObjectMapper j,
                               ChatStreamExecutor streams) {
        assistant = a;
        personal = p;
        json = j;
        this.streams = streams;
    }

    /**
     * 可选关联本人会话的知识问答；省略会话字段时保留单轮兼容入口。
     */
    @PostMapping("/chat")
    public AiResult chat(Authentication a, @RequestBody AiRequest r) {
        var actor = CurrentUser.from(a);
        var result = assistant.answer(actor, r);
        // 模型完成和 HTTP 发布存在间隔，发送前再核验身份、范围和来源。
        assistant.verifyDelivery(actor, r, result);
        return result;
    }

    /** 只读工具定义不授予调用权，模型执行时仍独立复核资源。 */
    @GetMapping("/tools")
    public org.springframework.http.ResponseEntity<List<ToolDefinition>> tools(Authentication a,
            @RequestParam(defaultValue = "KNOWLEDGE_QA") String taskType) {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(assistant.tools(CurrentUser.from(a), taskType));
    }

    /**
     * 先同步检查准入，再异步生成全文；处理心跳不带正文，只有校验结果才能分块发送。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Authentication a, @RequestBody AiRequest r) throws IOException {
        var actor = CurrentUser.from(a);
        // 会话所有者、参数和初始版本在返回 emitter 前验证，稳定准入错误仍为真实 HTTP 状态。
        assistant.validateRequest(actor, r);
        var emitter = new SseEmitter(60000L);
        var cancellation = new RequestCancellation();
        var delivery = new StreamDelivery(emitter, cancellation);
        emitter.onError(error -> delivery.cancel());
        emitter.onTimeout(delivery::cancel);
        emitter.onCompletion(delivery::cancel);
        delivery.send("progress", Map.of("stage", "processing"));
        delivery.heartbeat(streams.heartbeat(() -> delivery.pulse()));
        try {
            streams.execute(() -> {
                try {
                    var result = assistant.answer(actor, r, cancellation);
                    // 第一段正文前复核；断线和提交使用同一取消控制，已取消请求不保存新成功答案。
                    synchronized (delivery) {
                        cancellation.check();
                        assistant.verifyDelivery(actor, r, result);
                        delivery.send("progress", Map.of("stage", "validated"));
                        for (int i = 0; i < result.answer().length(); i += 512)
                            delivery.send("delta", Map.of("text", result.answer().substring(i,
                                    Math.min(i + 512, result.answer().length()))));
                        for (var citation : result.citations()) delivery.send("citation", citation);
                        delivery.done(result);
                    }
                } catch (LabException failure) {
                    delivery.failure(failure.code(), failure.getMessage());
                } catch (Exception failure) {
                    // 供应商与容器异常只输出稳定错误，不将凭证或内部栈交付客户端。
                    delivery.failure("INTERNAL_ERROR", "服务暂不可用");
                } finally {
                    delivery.finish();
                }
            });
        } catch (LabException admissionFailure) {
            delivery.cancel();
            throw admissionFailure;
        }
        return emitter;
    }

    /** 一个请求只用一个发送锁，心跳、正文与容器回调共享终止事实和事件序号。 */
    private static final class StreamDelivery {
        private final SseEmitter emitter;
        private final RequestCancellation cancellation;
        private long sequence = 1;
        private boolean closed;
        private ScheduledFuture<?> heartbeat;

        /** 绑定传输与跨模块取消控制，不把传输状态塞入模型文本。 */
        private StreamDelivery(SseEmitter emitter, RequestCancellation cancellation) {
            this.emitter = emitter;
            this.cancellation = cancellation;
        }

        /** 调度与快速完成可能竞态，已关闭时立即取消新登记的心跳。 */
        private synchronized void heartbeat(ScheduledFuture<?> heartbeat) {
            this.heartbeat = heartbeat;
            if (closed) heartbeat.cancel(false);
        }

        /** 每次发送先检查取消；发送失败立即终止后续正文和提交许可。 */
        private synchronized void send(String event, Object body) throws IOException {
            cancellation.check();
            if (closed) return;
            try {
                emitter.send(SseEmitter.event().id(Long.toString(sequence++)).name(event).data(body));
            } catch (IOException | IllegalStateException failedDelivery) {
                cancel();
                if (failedDelivery instanceof IOException io) throw io;
                throw new IOException("SSE 已结束", failedDelivery);
            }
        }

        /** 无正文心跳失败只撤销执行，不能冒充模型错误答案。 */
        private void pulse() {
            try {
                send("progress", Map.of("stage", "processing"));
            } catch (Exception disconnected) {
                cancel();
            }
        }

        /** 仅未终止的连接接收稳定 error；断线后的错误不再写入已关闭响应。 */
        private synchronized void failure(String code, String message) {
            if (closed) return;
            try {
                send("error", Map.of("code", code, "message", message));
            } catch (Exception disconnected) {
                cancel();
            }
        }

        /** done 与关闭共享发送锁，心跳不能插在最终事件和完成之间。 */
        private synchronized void done(AiResult result) throws IOException {
            send("done", result);
            finish();
        }

        /** 容器关闭、错误、超时与心跳失败撤销同一请求，取消事实先于后续提交检查。 */
        private synchronized void cancel() {
            closed = true;
            cancellation.cancel();
            if (heartbeat != null) heartbeat.cancel(false);
        }

        /** 正常或失败生成都关闭响应并释放心跳；SQL 成功不是浏览器确认收到的证明。 */
        private synchronized void finish() {
            boolean shouldComplete = !closed;
            cancel();
            if (shouldComplete) emitter.complete();
        }
    }

    /**
     * 笔记准备返回完整来源与待写内容，没有保存成功含义。
     */
    @PostMapping("/notes/prepare")
    public ApprovalSnapshot prepare(Authentication a, @Valid @RequestBody Requests.Note r) {
        return personal.prepare(CurrentUser.from(a), r.knowledgeBaseId(), r.title(), r.content(), r.sourceDependencies().stream().map(Requests.NoteSource::toDependency).toList());
    }

    /**
     * 本人预览重新核验所有来源。
     */
    @GetMapping("/approvals/{id}")
    public ApprovalSnapshot approval(Authentication a, @PathVariable String id) {
        return personal.approval(CurrentUser.from(a), id);
    }

    /**
     * 决定 API 不接收参数覆盖字段。
     */
    @PostMapping("/approvals/{id}/decision")
    public ApprovalSnapshot decision(Authentication a, @PathVariable String id, @Valid @RequestBody Requests.Decision r) {
        return personal.decide(CurrentUser.from(a), id, r.approved());
    }

    /**
     * 本人偏好列表。
     */
    @GetMapping("/memories")
    public List<MemorySnapshot> memories(Authentication a) {
        return personal.memories(CurrentUser.from(a));
    }

    /**
     * 显式用户命令保存偏好。
     */
    @PostMapping("/memories")
    public MemorySnapshot createMemory(Authentication a, @Valid @RequestBody Requests.MemoryCreate r) {
        return personal.createMemory(CurrentUser.from(a), r.content());
    }

    /**
     * 本人版本化更正。
     */
    @PatchMapping("/memories/{id}")
    public MemorySnapshot updateMemory(Authentication a, @PathVariable long id, @Valid @RequestBody Requests.MemoryUpdate r) {
        return personal.updateMemory(CurrentUser.from(a), id, r.version(), r.content());
    }

    /**
     * 删除后不再读取。
     */
    @DeleteMapping("/memories/{id}")
    public void deleteMemory(Authentication a, @PathVariable long id, @RequestParam long version) {
        personal.deleteMemory(CurrentUser.from(a), id, version);
    }

    /**
     * 运行列表只返回本人。
     */
    @GetMapping("/runs")
    public List<TraceSnapshot> runs(Authentication a, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return personal.runs(CurrentUser.from(a), page, size);
    }

    /**
     * 本人运行详情。
     */
    @GetMapping("/runs/{id}")
    public TraceSnapshot run(Authentication a, @PathVariable String id) {
        return personal.run(CurrentUser.from(a), id);
    }
}
