package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * 版本化 SectionSnapshot，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。
 */
public record SectionSnapshot(String sectionId, String parentSectionId, List<String> ancestorSectionIds,
                              String headingPath, int ordinal, int startOffset, int endOffset) {
    /**
     * 防御性复制列表，不能跨阶段修改证据。
     */
    public SectionSnapshot {
        ancestorSectionIds = List.copyOf(ancestorSectionIds);
    }
}
