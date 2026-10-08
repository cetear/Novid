package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * 版本化 ParsedDocument，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。
 */
public record ParsedDocument(List<SectionSnapshot> sections, List<ParentSnapshot> parents, List<ChunkSnapshot> chunks,
                             String configHash, String parserVersion, String splitPolicyVersion,
                             String mappingVersion, String tokenizerRef, String countSource) {
    /**
     * 兼容旧解析构造；未提供版本事实不能伪装成映射 v2。
     */
    public ParsedDocument(List<SectionSnapshot> sections, List<ParentSnapshot> parents, List<ChunkSnapshot> chunks, String configHash) {
        this(sections, parents, chunks, configHash, null, null, null, null, null);
    }

    /**
     * 防御性复制列表，不能跨阶段修改证据。
     */
    public ParsedDocument {
        sections = List.copyOf(sections);
        parents = List.copyOf(parents);
        chunks = List.copyOf(chunks);
    }
}
