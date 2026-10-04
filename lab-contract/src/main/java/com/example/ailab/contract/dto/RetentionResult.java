package com.example.ailab.contract.dto;

/** 单次有界维护的实际删除数量；不表示已完成全部积压或备份恢复。 */
public record RetentionResult(int tokens, int deduplications, int auditEvents, int structureRows) { }
