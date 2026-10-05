package com.example.ailab.data.persistence.po;

import com.baomidou.mybatisplus.annotation.*;
import java.time.Instant;

@TableName("auth_tokens")
public final class AuthTokenPo {
    @TableId(type = IdType.INPUT)
    public String tokenHash;
    public Long userId;
    public Long permissionVersion;
    public Instant expiresAt;
    public Boolean revoked;
}
