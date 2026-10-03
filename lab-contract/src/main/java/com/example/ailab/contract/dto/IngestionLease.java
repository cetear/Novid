package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 IngestionLease，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record IngestionLease(long ingestionId, long documentId, int documentVersion, long processingRevision, UserContext actor, String workerId, long fencingToken, String title, String format, String text) {
}
