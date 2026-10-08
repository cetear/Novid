package com.example.ailab.contract.dto;

import java.util.List;

/**
 * 单批持久向量事实；未知结果不允许自动重购，向量只按批读取。
 */
public record IngestionBatch(IngestionBatchPlan plan, String state, List<List<Float>> vectors, String modelVersion) {
}
