package com.example.ailab.contract.dto;

import java.util.*;

/** 恢复基线保存实际指令与工具契约，不保存凭证或可执行对象。 */
public record TaskExecutionBinding(int schemaVersion,String skillId,String skillVersion,String skillHash,
        Map<String,String> resources,Set<String> allowedTools,Map<String,String> toolContracts,String actionPolicyVersion) {
    public TaskExecutionBinding {
        resources=Map.copyOf(resources);allowedTools=Collections.unmodifiableSet(new TreeSet<>(allowedTools));toolContracts=Map.copyOf(toolContracts);
    }
}
