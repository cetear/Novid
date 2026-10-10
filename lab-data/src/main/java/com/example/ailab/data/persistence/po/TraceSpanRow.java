package com.example.ailab.data.persistence.po;

import java.sql.Timestamp;

/** 命名字段供MyBatis foreach逐行绑定，避免数组下标被当作顶层参数。 */
public record TraceSpanRow(String traceId, String spanId, String parentSpanId, int sequence,
                           String type, String status, Timestamp startedAt, Timestamp endedAt, String json) { }
