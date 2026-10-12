package com.example.ailab.contract.dto;

/** 可追加的执行额度；不改变冻结的来源、容量和业务计划。 */
public record ContentExecutionLimits(int turns, int attempts, int tools, int repairs, int executionSeconds) {
    public ContentExecutionLimits {
        if(turns<10||turns>10000||attempts<turns||attempts>20000||tools<1||tools>10000
                ||repairs<1||repairs>10000||executionSeconds<1200||executionSeconds>86400)
            throw new IllegalArgumentException("执行额度超出允许范围");
    }
    public static ContentExecutionLimits from(ContentWorkflow.Policy p) {
        return new ContentExecutionLimits(p.turns(),p.attempts(),p.tools(),p.turns(),p.executionSeconds());
    }
}
