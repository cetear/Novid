package com.example.ailab.contract.dto;
import java.util.List;
/** 请求意图；并非授权证明。空 SELECTED 表示零范围。 */
public record ScopeRequest(Mode mode, List<Long> knowledgeBaseIds, Long ownerUserId) {
    public enum Mode { SELF, SELECTED, ALL }
    /** 防御性复制，禁止调用后扩大请求范围。 */
    public ScopeRequest { mode = mode == null ? Mode.SELF : mode; knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds); }
    /** 缺省使用本人知识库。 */
    public static ScopeRequest self() { return new ScopeRequest(Mode.SELF, List.of(), null); }
}

