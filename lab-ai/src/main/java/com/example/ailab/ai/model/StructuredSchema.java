package com.example.ailab.ai.model;

import dev.langchain4j.model.chat.request.json.JsonSchema;
import com.example.ailab.contract.error.LabException;

import java.util.Set;

/**
 * 结构输出定义与服务端语义校验，共享有限修复协议。
 */
public interface StructuredSchema<T> {
    /**
     * 结构定义由服务端构建，不接受用户提供的任意Schema。
     */
    JsonSchema schema();

    /**
     * 提示约定与可用引用集合每次重装；JSON_OBJECT提供方仍需本地完整校验。
     */
    String instruction(Set<String> references);

    /**
     * 字段、枚举及引用都必须验证；不能仅依赖提供方约束生成。
     */
    T validate(String text, Set<String> references);

    /**
     * 仅回送程序生成的字段诊断；原始异常消息不作为模型指令。
     */
    default String repairInstruction(LabException failure) {
        return "上次输出未满足字段或引用约束，错误码="+failure.code()+"。字段诊断："+failure.validationIssues()+"。请按原参数和同一结构修正全部问题。";
    }
}
