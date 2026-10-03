package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** 版本化 ParsedDocument，原文位置以 UTF-16 offset 表示并保留 Unicode 边界。 */
public record ParsedDocument(List<SectionSnapshot> sections, List<ParentSnapshot> parents, List<ChunkSnapshot> chunks, String configHash) {
    /** 防御性复制列表，不能跨阶段修改证据。 */
    public ParsedDocument { sections=List.copyOf(sections); parents=List.copyOf(parents); chunks=List.copyOf(chunks); }
}
