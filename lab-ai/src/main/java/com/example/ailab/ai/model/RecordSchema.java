package com.example.ailab.ai.model;

import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** 服务端record定义JSON结构，业务验证器补充范围与引用约束。 */
public final class RecordSchema<T> implements StructuredSchema<T> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    private final Class<T> type;
    private final String instruction;
    private final Consumer<T> validate;
    public RecordSchema(Class<T> type, String instruction, Consumer<T> validate) {
        this.type = type; this.instruction = instruction; this.validate = validate;
    }
    public JsonSchema schema() { return JsonSchema.builder().name(type.getSimpleName()).rootElement(element(type)).build(); }
    private JsonSchemaElement element(Type type) {
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
        if (type == String.class) { if (!value.isTextual()) throw new IllegalArgumentException(); return; }
        if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
            if (!value.isArray()) throw new IllegalArgumentException();
            value.forEach(item -> check(item, list.getActualTypeArguments()[0])); return;
        }
        if (type instanceof Class<?> record && record.isRecord()) {
            if (!value.isObject() || value.size() != record.getRecordComponents().length) throw new IllegalArgumentException();
            for (var field : record.getRecordComponents()) {
                if (!value.has(field.getName())) throw new IllegalArgumentException();
                check(value.get(field.getName()), field.getGenericType());
            }
            return;
        }
        throw new IllegalArgumentException();
    }
    public String instruction(Set<String> references) { return instruction; }
    public T validate(String text, Set<String> references) {
        try {
            if (text == null || text.length() > 200000) throw new IllegalArgumentException();
            check(JSON.readTree(text), type);
            var value = JSON.readValue(text, type); validate.accept(value); return value;
        } catch (Exception invalid) { throw new LabException("MODEL_STRUCTURED_INVALID", "固定工作流输出结构、范围或来源不合法"); }
    }
}
