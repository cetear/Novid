package com.example.ailab.contract.dto;

import java.util.List;

/** 私人失败证据；只通过本人节点详情读取，不放入公共异常或外部遥测。 */
public record ValidationDiagnostic(String schemaId, String origin, List<Item> items,
        boolean collectionTruncated, boolean stoppedEarly) {
    public ValidationDiagnostic { items = List.copyOf(items); }
    public record Item(ValidationIssue issue, String pointer, String actualType, String excerptRaw,
            Long start, Long end, boolean excerptTruncated, Integer line, Integer column,
            String locationBasis) { }
}
