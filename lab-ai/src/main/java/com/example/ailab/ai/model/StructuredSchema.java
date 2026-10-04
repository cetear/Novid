package com.example.ailab.ai.model;

import dev.langchain4j.model.chat.request.json.JsonSchema;
import java.util.Set;

/** 后续计划／工具参数可复用的结构定义与程序语义校验，当前不执行工具或动态计划。 */
public interface StructuredSchema<T> {
    /** 结构定义由服务端构建，不接受用户提供的任意Schema。 */
    JsonSchema schema();
    /** 提示约定与可用引用集合每次重装；JSON_OBJECT提供方仍需本地完整校验。 */
    String instruction(Set<String> references);
    /** 字段、枚举及引用都必须验证；不能仅依赖提供方约束生成。 */
    T validate(String text, Set<String> references);
}
