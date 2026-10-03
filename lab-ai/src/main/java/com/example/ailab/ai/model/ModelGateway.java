package com.example.ailab.ai.model;

import com.example.ailab.contract.error.LabException;
import dev.langchain4j.model.openai.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import dev.langchain4j.model.output.FinishReason;

/**
 * 所有 chat 与 embedding 唯一入口；SDK 自动重试明确关闭。
 */
@Component
public class ModelGateway {
    public record Turn(String text, String modelId, Integer inputTokens, Integer outputTokens, boolean mock,
                       String finishReason) {
        /**
         * Mock 调用兼容构造，真实结果使用 SDK 提供的结束原因。
         */
        public Turn(String text, String modelId, Integer inputTokens, Integer outputTokens, boolean mock) {
            this(text, modelId, inputTokens, outputTokens, mock, "STOP");
        }
    }

    public record Vectors(List<List<Float>> vectors, String modelVersion, boolean mock) {
    }

    private record Health(int failures, long retryAfter) {
    }

    private final ModelRegistry registry;
    private final ConcurrentHashMap<String, Health> health = new ConcurrentHashMap<>();
    private final Semaphore concurrency = new Semaphore(4);

    /**
     * 只通过受控 Registry 获取实际目标。
     */
    public ModelGateway(ModelRegistry registry) {
        this.registry = registry;
    }

    /**
     * 最多三个尝试、一次主备切换；写工具不在本方法中执行或重放。
     */
    public Turn chat(String task, String system, String prompt, ExecutionBudget budget) {
        return chat(task, system, List.of(), prompt, budget);
    }

    /** 历史以 SDK 消息进入同一模型入口；摘要、主备与回答共享调用方的预算。 */
    public Turn chat(String task, String system, List<ChatMessage> history, String prompt, ExecutionBudget budget) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(SystemMessage.from(system));
        messages.addAll(history);
        messages.add(UserMessage.from(prompt));
        int inputBytes = bytes(system) + bytes(prompt) + 32 * messages.size();
        for (var message : history) inputBytes += bytes(message.toString());
        budget.turn();
        var ids = registry.candidates(task, Set.of("CHAT"));
        int attempts = 0;
        String failure = "MODEL_UNAVAILABLE";
        for (String id : ids.stream().limit(2).toList()) {
            var d = registry.definition(id);
            var h = health.get(id);
            if (h != null && h.retryAfter() > System.currentTimeMillis()) continue;
            for (int retry = 0; retry < 2 && attempts < 3; retry++) {
                attempts++;
                if (inputBytes + d.outputLimit() > d.contextWindow())
                    throw new LabException("BUDGET_EXCEEDED", "实际模型上下文不足，需缩减证据");
                if (!concurrency.tryAcquire()) throw new LabException("RATE_LIMITED", "模型并发已满");
                try {
                    budget.attempt();
                    if (registry.mock()) {
                        if (d.modelName().contains("timeout"))
                            throw new LabException("MODEL_TIMEOUT", "模拟主模型超时");
                        health.remove(id);
                        return new Turn("Mock 验证结果（非真实模型回答）：\n" + prompt, id, null, null, true);
                    }
                    var model = OpenAiChatModel.builder().baseUrl(d.endpoint()).apiKey(registry.credential(id)).modelName(d.modelName()).timeout(timeout(d, budget)).maxRetries(0).maxCompletionTokens(d.outputLimit()).logRequests(false).logResponses(false).build();
                    var response = model.chat(messages);
                    String text = response.aiMessage().text();
                    // 当前出口交付完整文本；截断、过滤、未知结束及工具续轮都不能伪装成功。
                    if (response.finishReason() != FinishReason.STOP || response.aiMessage().hasToolExecutionRequests()) {
                        throw new LabException("MODEL_INVALID_OUTPUT", "模型响应未正常完成，不能作为完整结果交付");
                    }
                    if (text == null || text.isBlank())
                        throw new LabException("MODEL_INVALID_OUTPUT", "模型未返回可交付文本");
                    health.remove(id);
                    var usage = response.tokenUsage();
                    return new Turn(text, id, usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount(), false, response.finishReason().name());
                } catch (RuntimeException error) {
                    failure = classify(error);
                    if (!Set.of("MODEL_TIMEOUT", "MODEL_UNAVAILABLE").contains(failure))
                        throw new LabException(failure, "模型调用失败");
                    final long now = System.currentTimeMillis();
                    health.compute(id, (key, previous) -> {
                        int count = previous == null ? 1 : previous.failures() + 1;
                        return new Health(count, count >= 3 ? now + 30000 : 0);
                    });
                    // 超时直接切到兼容备用，避免把整个期限消耗在同一失效目标。
                    if (failure.equals("MODEL_TIMEOUT")) break;
                } finally {
                    concurrency.release();
                }
            }
        }
        throw new LabException(ids.size() < 2 ? "NO_COMPATIBLE_FALLBACK" : failure, "模型暂不可用，未获得合法备用结果");
    }

    /**
     * embedding 单向量空间，无聊天主备；入库使用独立有限 attempts 预算。
     */
    public Vectors embed(List<String> texts, ExecutionBudget budget) {
        var ids = registry.candidates("EMBEDDING", Set.of("EMBEDDING"));
        String id = ids.get(0);
        var d = registry.definition(id);
        if (texts.isEmpty() || texts.size() > 32 || texts.stream().mapToInt(this::bytes).sum() > 16000)
            throw new LabException("INGESTION_BUDGET_EXCEEDED", "Embedding 批次输入超过限额");
        if (!concurrency.tryAcquire()) throw new LabException("RATE_LIMITED", "模型并发已满");
        try {
            budget.attempt();
            if (registry.mock()) {
                var vectors = new ArrayList<List<Float>>();
                for (String text : texts) {
                    float[] vector = new float[d.dimensions()];
                    text.codePoints().forEach(cp -> vector[Math.floorMod(cp, vector.length)] += 1);
                    double norm = 0;
                    for (float v : vector) norm += v * v;
                    norm = Math.sqrt(norm);
                    var result = new ArrayList<Float>();
                    for (float v : vector) result.add(norm == 0 ? 0 : (float) (v / norm));
                    vectors.add(List.copyOf(result));
                }
                return new Vectors(List.copyOf(vectors), d.modelName(), true);
            }
            var model = OpenAiEmbeddingModel.builder().baseUrl(d.endpoint()).apiKey(registry.credential(id)).modelName(d.modelName()).dimensions(d.dimensions()).timeout(timeout(d, budget)).maxRetries(0).maxSegmentsPerBatch(32).logRequests(false).logResponses(false).build();
            var result = model.embedAll(texts.stream().map(TextSegment::from).toList());
            var vectors = result.content().stream().map(e -> {
                var values = new ArrayList<Float>();
                for (float v : e.vector()) values.add(v);
                return List.copyOf(values);
            }).toList();
            if (vectors.size() != texts.size())
                throw new LabException("MODEL_INVALID_OUTPUT", "Embedding 返回数量不符");
            return new Vectors(vectors, d.modelName(), false);
        } catch (LabException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LabException(classify(e), "Embedding 调用失败");
        } finally {
            concurrency.release();
        }
    }

    /**
     * 输入窗口采用保守 UTF-8 字节上界；真实计费不使用此估计代替供应商 usage。
     */
    private int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 两种协议一致取目标超时、全局 30 秒上限和剩余期限的最小值。
     */
    private Duration timeout(ModelProperties.Definition definition, ExecutionBudget budget) {
        Duration remaining = budget.timeout();
        Duration configured = Duration.ofSeconds(definition.timeoutSeconds());
        return remaining.compareTo(configured) < 0 ? remaining : configured;
    }

    /**
     * 仅明确瞬时故障允许切换，认证、限流和参数失败不重试。
     */
    private String classify(RuntimeException e) {
        if (e instanceof LabException lab) return lab.code();
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String name = cause.getClass().getSimpleName();
            if (name.contains("Timeout")) return "MODEL_TIMEOUT";
            if (name.contains("Authentication") || name.contains("Unauthorized")) return "ACCESS_DENIED";
            if (name.contains("RateLimit")) return "RATE_LIMITED";
            if (name.contains("InternalServer") || cause instanceof java.io.IOException) return "MODEL_UNAVAILABLE";
        }
        return "MODEL_INVALID_OUTPUT";
    }
}
