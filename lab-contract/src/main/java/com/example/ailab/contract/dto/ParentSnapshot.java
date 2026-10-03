package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 ParentSnapshot，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record ParentSnapshot(String contextParentId, String sectionId, int ordinal, int startOffset, int endOffset) {
}
