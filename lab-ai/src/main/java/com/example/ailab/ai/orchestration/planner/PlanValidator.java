package com.example.ailab.ai.orchestration.planner;

import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 受限计划验证器：动作／角色／依赖均来自白名单，模型不能执行代码。
 */
@Component
public class PlanValidator {
    public record Step(String stepId, String action, String agentId, List<String> dependsOn) {
    }

    private static final Map<String, String> ROLES = Map.of("research", "ResearchWorker", "analysis", "AnalysisWorker", "report", "ReportWriter");

    /**
     * 固定上限八节点，拒绝重复 ID、未知动作、越权角色和依赖环。
     */
    public List<Step> validate(List<Step> plan) {
        if (plan == null || plan.isEmpty() || plan.size() > 8) throw LabException.invalid("计划必须为 1～8 节点");
        var byId = new LinkedHashMap<String, Step>();
        for (var step : plan) {
            if (step.stepId() == null || !step.stepId().matches("[A-Za-z0-9_-]{1,32}") || byId.put(step.stepId(), step) != null || !Objects.equals(ROLES.get(step.action()), step.agentId()) || step.dependsOn() == null || step.dependsOn().size() > 8)
                throw LabException.invalid("计划动作、角色、ID 或依赖不合法");
        }
        var sorted = new ArrayList<Step>();
        var done = new HashSet<String>();
        while (sorted.size() < plan.size()) {
            boolean progress = false;
            for (var s : plan)
                if (!done.contains(s.stepId()) && done.containsAll(s.dependsOn())) {
                    sorted.add(s);
                    done.add(s.stepId());
                    progress = true;
                }
            if (!progress) throw LabException.invalid("计划有环或缺失依赖");
        }
        return List.copyOf(sorted);
    }
}
