package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * UploadCommand 的跨模块不可变快照，不包含 ORM 对象。
 */
public record UploadCommand(long knowledgeBaseId, String title, String format, String text, String idempotencyKey) {
}

