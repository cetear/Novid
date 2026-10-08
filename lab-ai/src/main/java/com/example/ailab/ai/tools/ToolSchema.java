package com.example.ailab.ai.tools;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/**
 * 同一份Draft-7参数结构用于本地校验和SDK声明，未实现结构在注册时拒绝。
 */
public final class ToolSchema {
    public static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> KEYS = Set.of("type", "description", "title", "properties", "required", "additionalProperties",
            "items", "enum", "minLength", "maxLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties", "pattern", "$schema", "default", "examples");

    private ToolSchema() {
    }

    public static com.networknt.schema.JsonSchema compile(Map<String, Object> schema) {
        var node = JSON.valueToTree(schema);
        check(node, 0);
        if (!"object".equals(node.path("type").asText())) throw new IllegalArgumentException("工具参数须为object");
        // SDK根结构只有对象属性/必填/额外字段控制，其余根约束不能静默忽略。
        node.fieldNames().forEachRemaining(key -> {
            if (!Set.of("type", "properties", "required", "additionalProperties", "description", "title", "$schema").contains(key))
                throw new IllegalArgumentException("SDK未支持的根约束: " + key);
        });
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
    }

    private static void check(JsonNode node, int depth) {
        if (!node.isObject() || depth > 12) throw new IllegalArgumentException("工具Schema结构超限");
        node.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) throw new IllegalArgumentException("未支持的工具Schema关键字: " + key);
        });
        if (!Set.of("object", "array", "string", "integer", "number", "boolean", "null").contains(node.path("type").asText()))
            throw new IllegalArgumentException("工具Schema缺少或不支持type");
        if (node.has("$schema") && !Set.of("http://json-schema.org/draft-07/schema#", "https://json-schema.org/draft-07/schema#").contains(node.get("$schema").asText()))
            throw new IllegalArgumentException("工具Schema仅支持Draft-7");
        if (node.has("properties")) {
            if (!node.get("properties").isObject()) throw new IllegalArgumentException("工具properties无效");
            node.get("properties").elements().forEachRemaining(child -> check(child, depth + 1));
        }
        if (node.has("required")) {
            if (!node.get("required").isArray()) throw new IllegalArgumentException("工具required无效");
            var names = new HashSet<String>();
            for (var field : node.get("required"))
                if (!field.isTextual() || !node.path("properties").has(field.asText()) || !names.add(field.asText()))
                    throw new IllegalArgumentException("工具必填字段缺少定义或重复");
        }
        if (node.has("additionalProperties") && !node.get("additionalProperties").isBoolean())
            throw new IllegalArgumentException("工具additionalProperties须为布尔值");
        if ("array".equals(node.path("type").asText())) check(node.path("items"), depth + 1);
    }

    /**
     * 模型只获得原约束；raw子结构保留范围、枚举和嵌套对象，不静默丢约束。
     */
    public static ToolSpecification specification(ToolDefinition d) {
        compile(d.parameters());
        try {
            var builder = JsonObjectSchema.builder();
            var root = JSON.valueToTree(d.parameters());
            root.path("properties").fields().forEachRemaining(e -> builder.addProperty(e.getKey(), JsonRawSchema.from(e.getValue().toString())));
            if (root.has("additionalProperties"))
                builder.additionalProperties(root.get("additionalProperties").asBoolean());
            var required = new ArrayList<String>();
            root.path("required").forEach(n -> required.add(n.asText()));
            return ToolSpecification.builder().name(d.name()).description(d.description()).parameters(builder.required(required).build()).build();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("工具参数不能转换为SDK定义", error);
        }
    }

    public static JsonNode arguments(String text, com.networknt.schema.JsonSchema schema) {
        try {
            if (text == null || TextWindow.count(text) > 4096) throw LabException.invalid("工具参数超限");
            var node = JSON.readTree(text);
            if (node == null || !node.isObject() || !schema.validate(node).isEmpty())
                throw LabException.invalid("工具参数不符合Schema");
            return node;
        } catch (java.io.IOException error) {
            throw LabException.invalid("工具JSON参数不合法");
        }
    }

    /**
     * 用有序JSON固定契约摘要，嵌套参数不得因Map遍历顺序改变。
     */
    public static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));
        } catch (Exception failure) {
            throw new IllegalArgumentException("工具契约摘要失败", failure);
        }
    }
}
