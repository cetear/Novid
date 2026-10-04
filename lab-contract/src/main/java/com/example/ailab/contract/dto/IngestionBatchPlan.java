package com.example.ailab.contract.dto;

/** 固定顺序、稳定键和输入摘要；批次同时受项数与保守词元硬限额约束。 */
public record IngestionBatchPlan(int ordinal, int start, int count, String batchKey, String inputHash, int inputTokens) { }
