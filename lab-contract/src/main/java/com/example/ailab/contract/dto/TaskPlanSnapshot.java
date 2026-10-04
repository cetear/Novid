package com.example.ailab.contract.dto;

/** 本人计划事实；关注点、依赖和注册标识可见，内部角色提示词不可见。 */
public record TaskPlanSnapshot(TaskPlan plan, String planHash, String agentVersion, String modelId, String policyVersion) { }
