package com.example.ailab.ai.model;

import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 固定知识回答提取样例：结构合法、引用合法及NEEDS_INPUT语义一致才可交付。
 */
public final class KnowledgeAnswerSchema implements StructuredSchema<KnowledgeAnswerSchema.Answer> {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern CITATION = Pattern.compile("\\[(E[0-9]+)\\]");

    public record Answer(String status, String answer, List<String> references) {
    }

    /**
     * 同一定义既进入原生JSON Schema，也作为不支持原生Schema提供方的提示约定。
     */
    public JsonSchema schema() {
        return JsonSchema.builder().name("knowledge_answer_v1").rootElement(JsonObjectSchema.builder()
                .addEnumProperty("status", List.of("ANSWER", "NEEDS_INPUT"))
                .addStringProperty("answer")
                .addProperty("references", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                .required("status", "answer", "references").additionalProperties(false).build()).build();
    }

    /**
     * 不要求思维链，引用只来自本次实际交付的证据集合。
     */
    public String instruction(Set<String> references) {
        return "只返回JSON对象，字段严格为status（ANSWER或NEEDS_INPUT）、answer（非空字符串）、references（引用ID字符串数组）。"
                + "ANSWER的正文使用[E编号]，references与正文引用集合必须相同，至少引用一项已提供证据。"
                + "信息不足用NEEDS_INPUT且references为空、正文无引用。可用引用：" + new TreeSet<>(references) + "。禁止额外字段、Markdown围栏和执行事实。";
    }

    /**
     * 不自动移除围栏或忽略未知字段；引用在缩小备用窗口后重新验证。
     */
    public Answer validate(String text, Set<String> allowed) {
        try {
            if (text == null || text.length() > 40000) throw invalid();
            var root = JSON.readTree(text);
            if (!root.isObject() || root.size() != 3 || !root.has("status") || !root.has("answer") || !root.has("references")
                    || !root.get("status").isTextual() || !root.get("answer").isTextual() || !root.get("references").isArray())
                throw invalid();
            String status = root.get("status").asText(), answer = root.get("answer").asText();
            if (!Set.of("ANSWER", "NEEDS_INPUT").contains(status) || answer.isBlank() || root.get("references").size() > 6)
                throw invalid();
            var refs = new LinkedHashSet<String>();
            for (var ref : root.get("references")) {
                if (!ref.isTextual() || !allowed.contains(ref.asText()) || !refs.add(ref.asText())) throw invalid();
            }
            var actual = new HashSet<String>();
            var matcher = CITATION.matcher(answer);
            while (matcher.find()) actual.add(matcher.group(1));
            if (!actual.equals(refs) || status.equals("ANSWER") && !allowed.isEmpty() && refs.isEmpty()
                    || status.equals("NEEDS_INPUT") && !refs.isEmpty()) throw invalid();
            if (answer.contains("笔记已保存") || answer.contains("已成功保存笔记")) throw invalid();
            return new Answer(status, answer, List.copyOf(refs));
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw invalid();
        }
    }

    /**
     * 语义错误与服务故障分开，不触发健康开路或质量升级。
     */
    private LabException invalid() {
        return new LabException("MODEL_STRUCTURED_INVALID", "结构化字段、枚举或引用不合法");
    }
}
