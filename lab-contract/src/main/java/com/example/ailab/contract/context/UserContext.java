package com.example.ailab.contract.context;
/** 只能由有效登录读取当前账户状态后构造，不能由 HTTP 请求反序列化。 */
public record UserContext(long userId, Role role, boolean enabled, long permissionVersion, boolean passwordChangeRequired) {
    public enum Role { ADMIN, USER }
}

