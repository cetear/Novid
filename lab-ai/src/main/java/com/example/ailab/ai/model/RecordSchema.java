package com.example.ailab.ai.model;

import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** 服务端record定义JSON结构，业务验证器补充范围与引用约束。 */
public final class RecordSchema<T> implements StructuredSchema<T> {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RecordSchema.class);
    public enum Reason { JSON_SIZE, JSON_SYNTAX, JSON_FIELDS, JSON_TYPE, CONTENT_CONSTRAINT,
        FACT_STATUS, FACT_COUNT, FACT_ID, FACT_SOURCE, FACT_QUOTE, FACT_TEXT_SIZE }
    private static final class Violation extends RuntimeException {
        private final Reason reason;
        private Violation(Reason reason) { this.reason = reason; }
    }
    public static void require(boolean condition, Reason reason) {
        if (!condition) throw new Violation(reason);
    }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    private final Class<T> type;
    private final String instruction;
    private final Consumer<T> validate;
    private final String contract;
    public RecordSchema(Class<T> type, String instruction, Consumer<T> validate) {
        this.type = type; this.instruction = instruction; this.validate = validate;
        this.contract = contract(type).toString();
    }
    /** JSON对象模式不携带原生Schema，必须在消息中明确嵌套字段、类型和必填约束。 */
    private JsonNode contract(Type type) {
        var node = JSON.createObjectNode();
        if (type == int.class || type == Integer.class) return node.put("type", "integer");
        if (type == String.class) return node.put("type", "string");
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            node.put("type", "array"); node.set("items", contract(list.getActualTypeArguments()[0])); return node;
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            node.put("type", "object").put("additionalProperties", false);
            var fields = node.putObject("properties"); var required = node.putArray("required");
            for (var field : record.getRecordComponents()) {
                fields.set(field.getName(), contract(field.getGenericType())); required.add(field.getName());
            }
            return node;
        }
        throw new IllegalArgumentException("未支持的固定工作流JSON字段类型");
    }
    public JsonSchema schema() { return JsonSchema.builder().name(type.getSimpleName()).rootElement(element(type)).build(); }
    private JsonSchemaElement element(Type type) {
        if (type == int.class || type == Integer.class) return JsonIntegerSchema.builder().build();
        if (type == String.class) return JsonStringSchema.builder().build();
        if (type instanceof ParameterizedType list && list.getRawType() == List.class)
            return JsonArraySchema.builder().items(element(list.getActualTypeArguments()[0])).build();
        if (type instanceof Class<?> record && record.isRecord()) {
            var object = JsonObjectSchema.builder().additionalProperties(false);
            var names = new ArrayList<String>();
            for (var field : record.getRecordComponents()) { names.add(field.getName()); object.addProperty(field.getName(), element(field.getGenericType())); }
            return object.required(names).build();
        }
        throw new IllegalArgumentException("未支持的固定工作流JSON字段类型");
    }
    private void check(JsonNode value, Type type) {
        require(value != null && !value.isNull(), Reason.JSON_TYPE);
        if (type == int.class || type == Integer.class) { require(value.isIntegralNumber() && value.canConvertToInt(), Reason.JSON_TYPE); return; }
        if (type == String.class) { require(value.isTextual(), Reason.JSON_TYPE); return; }
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            require(value.isArray(), Reason.JSON_TYPE);
            value.forEach(item -> check(item, list.getActualTypeArguments()[0])); return;
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            require(value.isObject(), Reason.JSON_TYPE);
            require(value.size() == record.getRecordComponents().length, Reason.JSON_FIELDS);
            for (var field : record.getRecordComponents()) {
                require(value.has(field.getName()), Reason.JSON_FIELDS);
                check(value.get(field.getName()), field.getGenericType());
            }
            return;
        }
        throw new IllegalArgumentException();
    }
    public String instruction(Set<String> references) {
        return instruction + "\n仅输出满足以下JSON Schema的对象，全部字段必填，不允许额外字段、null或Markdown围栏：\n" + contract;
    }
    public T validate(String text, Set<String> references) {
        try {
            require(text != null && text.length() <= 200000, Reason.JSON_SIZE);
            check(JSON.readTree(text), type);
            var value = JSON.readValue(text, type); validate.accept(value); return value;
        } catch (Violation invalid) { throw invalid(invalid.reason); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw invalid(Reason.JSON_SYNTAX); }
        catch (Exception invalid) { throw invalid(Reason.CONTENT_CONSTRAINT); }
    }
    private LabException invalid(Reason reason) {
        LOG.warn("event=model.structured_invalid schema={} reason={}", type.getSimpleName(), reason);
        return new LabException("MODEL_STRUCTURED_INVALID", "固定工作流输出结构、范围或来源不合法 reason=" + reason);
    }
    /** 只回送服务端枚举诊断，不回送模型草稿、未知字段或Jackson异常消息。 */
    public String repairInstruction(LabException failure) {
        var reason = reason(failure).orElse(Reason.CONTENT_CONSTRAINT);
        String hint = switch (reason) {
            case FACT_QUOTE -> "quote必须逐字复制输入中的连续原文，保留空格与换行，不可改写或拼接。";
            case FACT_ID -> "id必须从指定prefix_i1起连续编号且不重复。";
            case FACT_SOURCE -> "sourceId必须与本次输入指定的sourceId完全一致。";
            case FACT_STATUS -> "status只能为COMPLETE或OVERFLOW。";
            case FACT_COUNT -> "items不得超过本批上限，容量不足时返回OVERFLOW。";
            case FACT_TEXT_SIZE -> "category/content/quote均不能为空，并遵守UTF-8字节上限。";
            default -> "按同一JSON Schema重新生成，检查所有必填字段、类型、范围与来源约束。";
        };
        return "\n上次输出校验失败 reason=" + reason + "。" + hint;
    }
    /** 调度仅识别本地协议产生的固定原因，未知结构拒绝不能触发更宽泛的自动重试。 */
    public static Optional<Reason> reason(LabException failure) {
        if (!failure.code().equals("MODEL_STRUCTURED_INVALID")) return Optional.empty();
        return Arrays.stream(Reason.values()).filter(r -> failure.getMessage() != null
                && failure.getMessage().equals("固定工作流输出结构、范围或来源不合法 reason=" + r)).findFirst();
    }
}
