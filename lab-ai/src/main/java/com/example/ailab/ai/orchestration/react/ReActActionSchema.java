package com.example.ailab.ai.orchestration.react;

import com.example.ailab.ai.model.StructuredSchema;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;
import java.util.Set;
import java.util.TreeSet;

/** 模型只选择下一动作名，任务参数、角色、权限与次数由程序提供。 */
public final class ReActActionSchema implements StructuredSchema<String> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Set<String> allowed;

    public ReActActionSchema(Set<String> allowed) { this.allowed = Set.copyOf(allowed); }
    public JsonSchema schema() {
        return JsonSchema.builder().name("react_next_action").rootElement(JsonObjectSchema.builder()
                .addEnumProperty("action", new TreeSet<>(allowed).stream().toList())
                .required("action").additionalProperties(false).build()).build();
    }
    public String instruction(Set<String> refs) {
        return "只输出一个JSON对象{\"action\":\"动作名\"}，action须属于" + new TreeSet<>(allowed)
                + "。依据当前观察选择一个动作，参数由服务端绑定。";
    }
    public String validate(String text, Set<String> refs) {
        try {
            if (text.length() > 256) throw new IllegalArgumentException();
            var node = JSON.readTree(text);
            if (!node.isObject() || node.size() != 1 || !node.path("action").isTextual()
                    || !allowed.contains(node.path("action").asText())) throw new IllegalArgumentException();
            return node.path("action").asText();
        } catch (Exception invalid) {
            throw new LabException("MODEL_STRUCTURED_INVALID", "ReAct动作不属于当前可用集合");
        }
    }
}
