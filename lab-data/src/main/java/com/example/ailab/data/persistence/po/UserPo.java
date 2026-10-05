package com.example.ailab.data.persistence.po;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 内部持久化对象，禁止离开 lab-data。
 */
@TableName("users")
public class UserPo {
    public com.example.ailab.contract.dto.UserSnapshot snapshot() {
        return new com.example.ailab.contract.dto.UserSnapshot(id, username,
                com.example.ailab.contract.context.UserContext.Role.valueOf(role), enabled, permissionVersion, passwordChangeRequired);
    }
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String passwordHash;
    public String role;
    public Boolean enabled;
    public Long permissionVersion;
    public Boolean passwordChangeRequired;
}
