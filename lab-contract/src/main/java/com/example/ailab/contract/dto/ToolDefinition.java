package com.example.ailab.contract.dto;

import java.util.*;

/** 工具只读定义；不包含执行地址、密钥或内部提示词。 */
public record ToolDefinition(String name, String version, String description, Map<String, Object> parameters,
                             String resultContract, String type, Set<String> requiredCapabilities,
                             boolean enabled, int timeoutSeconds, boolean retryable) {
    /** 注册元数据不可由请求修改，参数结构由程序固定构建。 */
    public ToolDefinition { parameters = Map.copyOf(parameters); requiredCapabilities = Set.copyOf(requiredCapabilities); }
}
