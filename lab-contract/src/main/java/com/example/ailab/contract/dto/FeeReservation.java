package com.example.ailab.contract.dto;

/** 服务端生成稳定尝试键，同一响应只补同一条费用事实。 */
public record FeeReservation(String operationId, String scopeId) { }
