package com.example.ailab.contract.dto;

import java.util.*;

/** 工具只读定义；不包含执行地址、密钥或内部提示词。 */
public record ToolDefinition(String name, String version, String description, Map<String, Object> parameters,
                             String resultContract, String type, Set<String> requiredCapabilities,
                             boolean enabled, int timeoutSeconds, boolean retryable) {
    /** 注册元数据不可由请求修改，参数结构由程序固定构建。 */
    public ToolDefinition { parameters = freezeMap(parameters); requiredCapabilities = Collections.unmodifiableSet(new TreeSet<>(requiredCapabilities)); }
    /** 嵌套Schema同样不可变，防止注册后参数约束被外部Map悄悄修改。 */
    private static Map<String,Object> freezeMap(Map<String,Object> value) {
        var copy=new LinkedHashMap<String,Object>();value.forEach((key,item)->copy.put(key,freeze(item)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object freeze(Object value) {
        if(value instanceof Map<?,?> map){var copy=new LinkedHashMap<String,Object>();map.forEach((k,v)->copy.put((String)k,freeze(v)));return Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> list)return Collections.unmodifiableList(list.stream().map(ToolDefinition::freeze).toList());
        return value;
    }
}
