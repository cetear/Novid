package com.example.ailab.ai.tools;

import com.example.ailab.contract.dto.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * 定义、可信策略与执行器一起注册，名称不能只声明而没有实现。
 */
public record RegisteredTool(ToolDefinition definition, String source, Set<String> tasks,
                             BooleanSupplier available, Executor executor) {
    public RegisteredTool {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(source);
        tasks = Set.copyOf(tasks);
        Objects.requireNonNull(available);
        Objects.requireNonNull(executor);
        if (source.isBlank() || tasks.isEmpty()) throw new IllegalArgumentException("工具来源或任务为空");
    }

    /**
     * 参数已校验；上下文身份和范围由调用方绑定，不从模型JSON提取。
     */
    @FunctionalInterface
    public interface Executor {
        Result execute(ToolInvocationContext context, JsonNode arguments) throws Exception;
    }

    /**
     * 业务事实与可选知识证据分开；普通MCP工具无需伪造知识文档。
     */
    public record Result(String text, List<EvidenceBundle> evidence) {
        public Result {
            Objects.requireNonNull(text);
            evidence = List.copyOf(evidence);
        }

        public static Result text(String value) {
            return new Result(value, List.of());
        }
    }
}
