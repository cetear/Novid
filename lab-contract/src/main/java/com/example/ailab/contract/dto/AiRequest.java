package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * AiRequest 的跨模块不可变快照，不包含 ORM 对象。
 */
public record AiRequest(String question, ScopeRequest scope, Long sessionId, Long sessionVersion,
                        String modelProfile, String responseFormat, String toolMode) {
    /**
     * S04六参调用保持固定流程，工具续轮由显式选项启用。
     */
    public AiRequest(String question, ScopeRequest scope, Long sessionId, Long sessionVersion, String modelProfile, String responseFormat) {
        this(question, scope, sessionId, sessionVersion, modelProfile, responseFormat, null);
    }

    /**
     * S01旧调用兼容；新选项只接受逻辑profile，STRUCTURED为可选结构回答。
     */
    public AiRequest(String question, ScopeRequest scope, Long sessionId, Long sessionVersion) {
        this(question, scope, sessionId, sessionVersion, null, null);
    }

    /**
     * 格式本地校验，错误不能走到付费模型；profile白名单由AI服务端校验。
     */
    public AiRequest {
        if (toolMode != null && !java.util.Set.of("OFF", "READ_ONLY").contains(toolMode)
                || "READ_ONLY".equals(toolMode) && "STRUCTURED".equals(responseFormat))
            throw com.example.ailab.contract.error.LabException.invalid("toolMode须为OFF或READ_ONLY，续轮仅支持TEXT");
        if (responseFormat != null && !java.util.Set.of("TEXT", "STRUCTURED").contains(responseFormat))
            throw com.example.ailab.contract.error.LabException.invalid("responseFormat须为TEXT或STRUCTURED");
        if (modelProfile != null && !modelProfile.matches("[a-z][a-z0-9-]{0,63}"))
            throw com.example.ailab.contract.error.LabException.invalid("模型逻辑选项格式不合法");
    }

    /**
     * 旧单轮调用保持源代码与 JSON 兼容，会话字段必须由新调用成对提供。
     */
    public AiRequest(String question, ScopeRequest scope) {
        this(question, scope, null, null);
    }
}

