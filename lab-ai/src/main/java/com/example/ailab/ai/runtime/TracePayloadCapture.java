package com.example.ailab.ai.runtime;

import com.example.ailab.contract.context.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.*;
import java.util.*;
import java.util.regex.Pattern;

/** 私人快照仅登记输入和响应的内容，执行元数据由追踪节点单独保存。 */
public final class TracePayloadCapture {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<String> METADATA = Set.of("id","role","type","status","decision","relation","kind","layout","seconds",
            "schema","properties","required","additionalproperties","policy","skill","workflow","toolcontracts","scope",
            "route","usage","tokens","inputtokens","outputtokens","estimatedoutputbytes","outputbytes","version","processingrevision",
            "metadata","debug","config","configuration","corrections","tasktype","format","protocol","attempts","elapsedexecutionseconds",
            "offset","startoffset","endoffset","textlength","payloadsources","errorcode","httpstatus","retryafterseconds","causes",
            "code","unit","actual","maximum","path","rule","requestedunits","requestedquestions","contentrole","directorypolicy",
            "imagepolicy","imagemode","coveragepolicy","cognition","priority","generatedat","createdat","completedat");
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern SECRET = Pattern.compile("(?i)([\"']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|authorization|client[_-]?secret)[\"']?\\s*[=:]\\s*[\"']?)[^\\s,;\\\"'}]+|\\bsk-[A-Za-z0-9_-]{8,}|(?<=[?&])((?:x-amz-signature|x-goog-signature|sig|signature)=)[^&#\\s]+");
    private TracePayloadCapture() { }
    public static void input(TraceContext.Span span, Object value) { capture(span, value, true); }
    public static void output(TraceContext.Span span, Object value) { capture(span, value, false); }

    public static void messages(TraceContext.Span span, List<ChatMessage> messages) {
        if (!span.context().capturesPayloads()) return;
        var content = new ArrayList<String>();
        for (var message : messages) {
            if (message instanceof SystemMessage system) {
                String text = system.text();
                int schema = text.indexOf("\n仅输出满足以下JSON Schema");
                content.add(schema < 0 ? text : text.substring(0,schema));
            } else if (message instanceof UserMessage user) {
                for (var part : user.contents()) if (part instanceof TextContent text) content.add(text.text());
            } else if (message instanceof AiMessage ai) {
                if (ai.text() != null) content.add(ai.text());
                if (ai.hasToolExecutionRequests()) ai.toolExecutionRequests().forEach(tool -> content.add(tool.arguments()));
            } else if (message instanceof ToolExecutionResultMessage result) content.add(result.text());
        }
        input(span,content);
    }

    private static void capture(TraceContext.Span span, Object value, boolean input) {
        if (!span.context().capturesPayloads() || value == null) return;
        try {
            var content = new ArrayList<String>();
            collect(value instanceof String text ? parse(text) : JSON.valueToTree(value),content,0);
            String text = clean(String.join("\n",content));
            if (text.isBlank()) return;
            if (input) span.input(text); else span.output(text);
        } catch (Exception ignored) { /* 排错快照失败不影响业务执行。 */ }
    }
    private static JsonNode parse(String text) {
        String json = text.strip();int line = json.indexOf('\n');
        if (line >= 0 && (json.substring(0,line).strip().equals("```json") || json.substring(0,line).strip().equals("```")) && json.endsWith("```"))
            json = json.substring(line+1,json.length()-3).strip();
        try { return JSON.readTree(json); }
        catch (Exception plain) { return JSON.getNodeFactory().textNode(text); }
    }
    private static void collect(JsonNode node,List<String> content,int depth) {
        if (node == null || node.isNull() || depth > 64) return;
        if (node.isTextual()) {
            String text = node.textValue();
            // 展开回执中的JSON正文，不登记json/modelId/corrections外壳。
            String stripped = text.strip();
            if (stripped.startsWith("{") || stripped.startsWith("[") || stripped.startsWith("```json")) {
                var parsed = parse(text);
                if (!parsed.isTextual()) { collect(parsed,content,depth+1);return; }
            }
            if (!text.isBlank()) content.add(text);
        } else if (node.isObject()) {
            if (node.has("properties") && node.has("type")) return;
            if (node.has("kind") && Set.of("CALL_FAILURE","VALIDATION_FAILURE").contains(node.path("kind").asText())) return;
            node.fields().forEachRemaining(field -> {
                String key = field.getKey().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
                if (!metadata(key,field.getValue())) collect(field.getValue(),content,depth+1);
            });
        } else if (node.isArray()) {
            for (var value : node) collect(value,content,depth+1);
        } else if (node.isNumber() || node.isBoolean()) content.add(node.asText());
    }
    private static boolean metadata(String key,JsonNode value) {
        return METADATA.contains(key) || key.endsWith("id") || key.endsWith("ids") || key.endsWith("hash") || key.endsWith("checksum")
                || key.contains("password") || key.contains("secret") || key.contains("credential") || key.contains("authorization")
                || Set.of("apikey","token","accesstoken","refreshtoken","cookie","setcookie").contains(key)
                || key.equals("plan") && value.isValueNode();
    }
    public static String redact(String value) { return value == null ? null : clean(value); }
    private static String clean(String value) {
        return SECRET.matcher(BEARER.matcher(value).replaceAll("Bearer [REDACTED]")).replaceAll(match ->
                java.util.regex.Matcher.quoteReplacement((match.group(1) != null ? match.group(1)
                        : match.group(2) != null ? match.group(2) : "") + "[REDACTED]"));
    }
}
