package com.example.ailab.ai.orchestration.fixed;

import com.example.ailab.ai.orchestration.planexecute.PlanValidator;

import java.util.List;

/** 固定报告流程定义：两类资料角色并行，报告角色汇合；不调用模型规划。 */
public final class FixedReportPlan {
    private FixedReportPlan() { }

    /** 沿用原有稳定步骤ID和计划校验数据结构，保持检查点及恢复语义。 */
    public static List<PlanValidator.Step> steps() {
        return List.of(
                new PlanValidator.Step("research", "research", "ResearchWorker", List.of()),
                new PlanValidator.Step("analysis", "analysis", "AnalysisWorker", List.of()),
                new PlanValidator.Step("report", "report", "ReportWriter", List.of("research", "analysis")));
    }
}
