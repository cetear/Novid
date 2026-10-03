package com.example.ailab.web.security;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.error.LabException;
import org.springframework.security.core.Authentication;

/**
 * 可信身份只从服务端 SecurityContext 读取。
 */
public final class CurrentUser {
    /**
     * 禁止实例化工具类。
     */
    private CurrentUser() {
    }

    /**
     * 不接受请求体 userId/role，也不接受匿名主体。
     */
    public static UserContext from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserContext user))
            throw new LabException("AUTH_REQUIRED", "请先登录");
        return user;
    }
}
