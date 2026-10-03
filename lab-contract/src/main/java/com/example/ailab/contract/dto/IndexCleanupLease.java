package com.example.ailab.contract.dto;

/** 索引清理的不可变删除边界；不会随新的 active revision 扩大删除范围。 */
public record IndexCleanupLease(long eventId,String eventType,long resourceId,long resourceVersion,
                               int documentVersion,long processingRevision,String workerId,long fencingToken) {}
