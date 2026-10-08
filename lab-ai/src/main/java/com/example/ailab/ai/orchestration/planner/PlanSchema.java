package com.example.ailab.ai.orchestration.planner;

import com.example.ailab.ai.model.StructuredSchema;
import com.example.ailab.contract.dto.TaskPlan;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/**
 * 结构和语义都由程序校验，无效计划只允许使用原共享结构修复额度一次。
 */
public final class PlanSchema implements StructuredSchema<TaskPlan> {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final PlanValidator validator;

    /**
     * 固定任务类型参与校验，计划不能自行改成媒体或写入任务。
     */
    public PlanSchema(PlanValidator validator) {
        this.validator = validator;
    }

    /**
     * 同一服务端Schema供原生或JSON对象提供方使用。
     */
    public JsonSchema schema() {
        var input = JsonObjectSchema.builder().addStringProperty("focus").required("focus").additionalProperties(false).build();
        var node = JsonObjectSchema.builder().addEnumProperty("stepId", List.of("research", "analysis", "report"))
                .addEnumProperty("action", List.of("research", "analysis", "report"))
                .addEnumProperty("agentId", List.of("ResearchWorker", "AnalysisWorker", "ReportWriter"))
                .addEnumProperty("taskType", List.of("RESEARCH_REPORT"))
                .addProperty("dependsOn", JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build())
                .addProperty("input", input).addEnumProperty("qualityRequirement", List.of("SOURCE_GROUNDED"))
                .required("stepId", "action", "agentId", "taskType", "dependsOn", "input", "qualityRequirement").additionalProperties(false).build();
        return JsonSchema.builder().name("research_plan_s05_v1").rootElement(JsonObjectSchema.builder()
                .addEnumProperty("version", List.of("plan-s05-v1"))
                .addProperty("steps", JsonArraySchema.builder().items(node).build())
                .required("version", "steps").additionalProperties(false).build()).build();
    }

    /**
     * 只输出动作数据，不要求思维链；依赖由目标是否需要前序结果决定。
     */
    public String instruction(Set<String> references) {
        return "仅返回JSON对象，顶层字段只有version=plan-s05-v1和steps数组。恰好research、analysis、report三节点，stepId等于action。"
                + "每个节点字段必须完整且仅为stepId、action、agentId、taskType、dependsOn、input、qualityRequirement。"
                + "角色字段名必须为agentId（禁止role或agent等别名），分别ResearchWorker、AnalysisWorker、ReportWriter，taskType均RESEARCH_REPORT。"
                + "action与stepId都必须使用英文注册常量research、analysis、report，禁止把动作写成中文描述；描述仅放input.focus。"
                + "例如research节点为{\"stepId\":\"research\",\"action\":\"research\",\"agentId\":\"ResearchWorker\",\"taskType\":\"RESEARCH_REPORT\",\"dependsOn\":[],\"input\":{\"focus\":\"研究关注点\"},\"qualityRequirement\":\"SOURCE_GROUNDED\"}。其他节点同形，关注点与依赖按主题决定。"
                + "dependsOn为节点stepId字符串数组，input={focus:不超过400字符的关注点}，qualityRequirement=SOURCE_GROUNDED。"
                + "report必须依赖research和analysis；研究与统计独立时两者dependsOn为空，可并行；"
                + "主题明确要求先研究再分析时analysis依赖research，反之可让research依赖analysis。禁止环。"
                + "不要附加权限、Scope、SQL、代码、endpoint、modelId、密钥、批准或预算字段。";
    }

    /**
     * 未知字段、错类型、超长输入与非法DAG均属于结构错误，不能执行。
     */
    public TaskPlan validate(String text, Set<String> references) {
        try {
            if (text == null || text.length() > 8000) throw new IllegalArgumentException();
            var root = JSON.readTree(text);
            if (!root.isObject() || !root.path("version").isTextual() || !root.path("steps").isArray())
                throw new IllegalArgumentException();
            for (var node : root.get("steps")) {
                for (String field : List.of("stepId", "action", "agentId", "taskType", "qualityRequirement"))
                    if (!node.path(field).isTextual()) throw new IllegalArgumentException();
                if (!node.path("input").isObject() || !node.path("input").path("focus").isTextual() || !node.path("dependsOn").isArray())
                    throw new IllegalArgumentException();
                for (var dependency : node.get("dependsOn"))
                    if (!dependency.isTextual()) throw new IllegalArgumentException();
            }
            var plan = JSON.readValue(text, TaskPlan.class);
            validator.validate(plan);
            return plan;
        } catch (Exception invalid) {
            throw new LabException("MODEL_STRUCTURED_INVALID", "受限计划结构、动作、角色或依赖不合法");
        }
    }
}
