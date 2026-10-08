package com.example.ailab.contract.dto;

import java.util.*;

/** 恢复基线保存实际指令与工具契约，不保存凭证或可执行对象。 */
public record TaskExecutionBinding(int schemaVersion,String skillId,String skillVersion,String skillHash,
        Map<String,String> resources,Set<String> allowedTools,Map<String,String> toolContracts,String actionPolicyVersion) {
    public TaskExecutionBinding {
        resources=Map.copyOf(resources);allowedTools=Collections.unmodifiableSet(new TreeSet<>(allowedTools));toolContracts=Map.copyOf(toolContracts);
    }
    /** 关闭Skill时也固定运行模式，防止恢复时因开启配置而切换流程。 */
    public static TaskExecutionBinding legacy() {
        return new TaskExecutionBinding(0,"legacy-ppt","1","",Map.of(),Set.of(),Map.of(),"legacy-ppt-v1");
    }
    public boolean legacyMode() {return equals(legacy());}
}
