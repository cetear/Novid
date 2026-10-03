package com.example.ailab.data.persistence.po;

import com.baomidou.mybatisplus.annotation.*;

/**
 * 内部持久化对象，禁止离开 lab-data。
 */
@TableName("users")
public class UserPo {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String username;
    public String passwordHash;
    public String role;
    public Boolean enabled;
    public Long permissionVersion;
    public Boolean passwordChangeRequired;
}
