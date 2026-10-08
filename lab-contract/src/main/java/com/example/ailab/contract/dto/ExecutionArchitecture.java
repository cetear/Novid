package com.example.ailab.contract.dto;

/** 服务端执行架构；预留值只有登记了工作流与执行器后才能运行。 */
public enum ExecutionArchitecture {
    REACT, PLAN_EXECUTE, FIXED, MULTI_AGENT
}
