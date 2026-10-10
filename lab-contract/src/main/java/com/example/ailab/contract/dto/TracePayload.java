package com.example.ailab.contract.dto;

/** 本人排错内容快照；截断明确标记，禁止通过日志对象展开正文。 */
public record TracePayload(String content, boolean truncated, int originalChars) {
    public TracePayload {
        if (content == null || content.length() > 16384 || originalChars < content.length())
            throw new IllegalArgumentException("节点快照超限");
    }
    @Override public String toString() { return "TracePayload[truncated=" + truncated + ", originalChars=" + originalChars + "]"; }
}
