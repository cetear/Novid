package com.example.ailab.ai.runtime;

import com.example.ailab.contract.context.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Locale;
import java.util.regex.Pattern;

/** 私人排错快照统一过滤；失败静默略过，不记录请求头、配置对象或原始HTTP响应。 */
public final class TracePayloadCapture {
    // 非法JSON保留文本进入脱敏；不能覆盖重复字段或只保存第一个对象，丢掉校验失败证据。
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET = Pattern.compile("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|authorization|client[_-]?secret)[\"']?\\s*[=:]\\s*[\"']?)[^\\s,;\\\"'}]+|\\bsk-[A-Za-z0-9_-]{8,}|(?<=[?&])((?:x-amz-signature|x-goog-signature|sig|signature)=)[^&#\\s]+");
    private TracePayloadCapture() { }
    public static void input(TraceContext.Span span, Object value) { capture(span, value, true); }
    public static void output(TraceContext.Span span, Object value) { capture(span, value, false); }
    private static void capture(TraceContext.Span span, Object value, boolean input) {
        if (!span.context().capturesPayloads() || value == null) return;
        try {
            JsonNode node;
            if (value instanceof String s) {
                try { node = JSON.readTree(s); }
                catch (Exception plain) { node = JSON.getNodeFactory().textNode(s); }
            } else node = JSON.valueToTree(value);
            filter(node);
            String content = node.isTextual() ? clean(node.textValue()) : clean(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            if (input) span.input(content); else span.output(content);
        } catch (Exception ignored) { /* 不让排错编码失败中断执行。 */ }
    }
    private static void filter(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var names = new java.util.ArrayList<String>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                String key = name.replaceAll("[-_]", "").toLowerCase(Locale.ROOT);
                if (key.contains("password") || key.contains("secret") || key.contains("credential") || key.contains("authorization")
                        || key.equals("apikey") || key.equals("token") || key.equals("accesstoken") || key.equals("refreshtoken")
                        || key.equals("cookie") || key.equals("setcookie")) object.put(name, "[REDACTED]");
                else if (object.get(name).isTextual()) object.put(name, clean(object.get(name).textValue()));
                else filter(object.get(name));
            }
        } else if (node.isArray()) {
            var array = (com.fasterxml.jackson.databind.node.ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                if (array.get(i).isTextual()) array.set(i, JSON.getNodeFactory().textNode(clean(array.get(i).textValue())));
                else filter(array.get(i));
            }
        }
    }
    private static String clean(String value) {
        return SECRET.matcher(BEARER.matcher(value).replaceAll("Bearer [REDACTED]")).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement((match.group(1) != null ? match.group(1)
                        : match.group(2) != null ? match.group(2) : "") + "[REDACTED]"));
    }
}
