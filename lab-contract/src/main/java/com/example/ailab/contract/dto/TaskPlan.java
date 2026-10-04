package com.example.ailab.contract.dto;

import java.util.*;

/** S05有限研究计划；动作数据不包含可执行对象、提示词或模型端点。 */
public record TaskPlan(String version, List<Node> steps) {
    /** 保持已批准执行的计划不可变，恢复不能改依赖或输入。 */
    public TaskPlan { steps = List.copyOf(steps); }
    public record Node(String stepId, String action, String agentId, String taskType, List<String> dependsOn,
                       Map<String, String> input, String qualityRequirement) {
        /** 服务端补身份／模型／范围，模型只能提供有限任务关注点。 */
        public Node { dependsOn = List.copyOf(dependsOn); input = Map.copyOf(input); }
    }
}
