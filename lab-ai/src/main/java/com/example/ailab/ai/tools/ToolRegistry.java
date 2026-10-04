package com.example.ailab.ai.tools;

import com.example.ailab.contract.dto.ToolDefinition;
import com.example.ailab.contract.error.LabException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.*;
import java.util.*;

/** 显式注册定义和执行标识；不扫描服务或反射调用模型指定的类。 */
public final class ToolRegistry {
    private final Map<String, ToolDefinition> definitions;
    /** 重名、非法结构、缺执行器在构造时失败，执行集合不能超出程序白名单。 */
    public ToolRegistry(List<ToolDefinition> values, Set<String> executors) {
        var registered = new LinkedHashMap<String, ToolDefinition>();
        for (var d : values) {
            if (d == null || !d.name().matches("[a-z_]{1,64}") || d.version().isBlank() || d.description().isBlank()
                    || !Set.of("READ", "WRITE").contains(d.type()) || d.timeoutSeconds() < 1 || d.timeoutSeconds() > 30
                    || !executors.contains(d.name()) || registered.putIfAbsent(d.name(), d) != null
                    || !"object".equals(d.parameters().get("type")) || !Boolean.FALSE.equals(d.parameters().get("additionalProperties"))
                    || !(d.parameters().get("properties") instanceof Map<?, ?>) || !(d.parameters().get("required") instanceof List<?>))
                throw new IllegalArgumentException("工具注册定义、结构、名称或执行器不合法");
            specification(d); // 同一参数结构必须可转换为正式SDK定义。
        }
        definitions = Collections.unmodifiableMap(registered);
    }
    /** 固定不可变顺序用于请求暴露与验收。 */
    public List<ToolDefinition> all() { return List.copyOf(definitions.values()); }
    /** 未知工具明确拒绝，不尝试反射解析。 */
    public ToolDefinition require(String name) {
        var result = definitions.get(name);
        if (result == null) throw new LabException("UNKNOWN_TOOL", "未知工具");
        return result;
    }
    /** 参数白名单转换为SDK结构；本地仍校验语义与最大长度。 */
    public static ToolSpecification specification(ToolDefinition d) {
        var object = JsonObjectSchema.builder().additionalProperties(false);
        var properties = (Map<?, ?>) d.parameters().get("properties");
        for (var entry : properties.entrySet()) {
            String name = (String) entry.getKey(); var field = (Map<?, ?>) entry.getValue();
            if ("string".equals(field.get("type"))) object.addStringProperty(name);
            else if ("integer".equals(field.get("type"))) object.addIntegerProperty(name);
            else throw new IllegalArgumentException("工具Schema包含未实现类型");
        }
        var required = (List<?>) d.parameters().get("required");
        if (!properties.keySet().containsAll(required)) throw new IllegalArgumentException("工具必填字段缺少定义");
        return ToolSpecification.builder().name(d.name()).description(d.description())
                .parameters(object.required(required.stream().map(String.class::cast).toList()).build()).build();
    }
}
