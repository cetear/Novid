package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * AiRequest 的跨模块不可变快照，不包含 ORM 对象。
 */
public record AiRequest(String question, ScopeRequest scope, Long sessionId, Long sessionVersion) {
    /** 旧单轮调用保持源代码与 JSON 兼容，会话字段必须由新调用成对提供。 */
    public AiRequest(String question, ScopeRequest scope) {
        this(question, scope, null, null);
    }
}

