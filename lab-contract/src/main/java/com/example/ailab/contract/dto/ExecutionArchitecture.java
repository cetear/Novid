package com.example.ailab.contract.dto;

/** 服务端登记的独立执行架构；任务绑定后不能由客户端或模型切换。 */
public enum ExecutionArchitecture { FIXED, REACT, PLAN_EXECUTE, MULTI_AGENT }
