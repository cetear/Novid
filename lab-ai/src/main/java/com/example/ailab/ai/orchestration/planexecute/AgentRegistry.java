package com.example.ailab.ai.orchestration.planexecute;

import java.util.*;

/**
 * 仅登记S05已实现角色，媒体角色留给后续阶段。
 */
public final class AgentRegistry {
    public record Definition(String agentId, String version, String responsibility, Set<String> taskTypes,
                             String inputContract, String outputContract, String modelProfile, Set<String> tools,
                             int maxTurns, int maxCalls) {
    }

    private static final Map<String, Definition> ROLES = Map.of(
            "research", new Definition("ResearchWorker", "s05-v1", "授权章节研究", Set.of("RESEARCH_REPORT"), "bounded-page-v1", "source-summary-v1", "economy", Set.of("get_document"), 3, 4),
            "analysis", new Definition("AnalysisWorker", "s05-v1", "真实统计解释", Set.of("RESEARCH_REPORT"), "statistics-v1", "analysis-v1", "analysis", Set.of("get_knowledge_statistics"), 1, 1),
            "report", new Definition("ReportWriter", "s05-v1", "合法结果汇合", Set.of("RESEARCH_REPORT"), "checkpoint-pair-v1", "report-v1", "report", Set.of(), 1, 0));
    private static final Definition PLANNER = new Definition("Planner", "s05-v1", "受限动作与依赖规划", Set.of("RESEARCH_REPORT"),
            "topic-document-count-v1", "plan-s05-v1", "planning", Set.of(), 2, 0);

    /**
     * 按白名单动作找到唯一执行角色，不能按模型类名装配。
     */
    public static Definition require(String action) {
        var result = ROLES.get(action);
        if (result == null) throw com.example.ailab.contract.error.LabException.invalid("未知计划动作");
        return result;
    }

    /**
     * 角色元数据只读，用于校验和后续观测。
     */
    public static Collection<Definition> all() {
        var result = new ArrayList<>(ROLES.values());
        result.add(PLANNER);
        return List.copyOf(result);
    }

    /**
     * 工具类不允许创建动态注册表。
     */
    private AgentRegistry() {
    }
}
