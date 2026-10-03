package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * LoginResult 的跨模块不可变快照，不包含 ORM 对象。
 */
public record LoginResult(String token, Instant expiresAt, UserSnapshot user) {
}

