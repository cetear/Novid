package com.example.ailab.contract.dto;

/**
 * 仅低基数系统计数，不携带任何用户／任务／运行标识或内容。
 */
public record OperationalMetrics(long runs, long failedRuns, long incompleteRuns,
                                 long queuedTasks, long pendingOutbox, long accessEvents,
                                 long queryCacheHits, long queryCacheMisses, long queryCacheEntries) {
}
