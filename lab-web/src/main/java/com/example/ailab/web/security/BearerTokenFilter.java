package com.example.ailab.web.security;

import com.example.ailab.business.application.AccountApplicationService;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.*;

/**
 * 正式随机 Bearer token；每请求数据库核验，不采用固定教学令牌。
 */
public class BearerTokenFilter extends OncePerRequestFilter {
    private final AccountApplicationService accounts;
    private final ObjectMapper json;

    /**
     * Filter 由安全链显式创建，避免 Servlet 容器重复注册。
     */
    public BearerTokenFilter(AccountApplicationService accounts, ObjectMapper json) {
        this.accounts = accounts;
        this.json = json;
    }

    /**
     * 登录和仅存活健康检查无须已有 token。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        return path.equals("/api/v1/auth/login") || path.equals("/actuator/health");
    }

    /**
     * 首次临时密码只能查看身份、改密和退出。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        try {
            if (header == null || !header.startsWith("Bearer ")) throw new LabException("AUTH_REQUIRED", "请先登录");
            var user = accounts.authenticate(header.substring(7));
            if (user.passwordChangeRequired() && !Set.of("/api/v1/auth/me", "/api/v1/auth/password", "/api/v1/auth/logout").contains(req.getServletPath()))
                throw new LabException("PASSWORD_CHANGE_REQUIRED", "请先修改临时密码");
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name()))));
        } catch (LabException e) {
            res.setStatus(e.code().equals("AUTH_REQUIRED") ? 401 : 403);
            res.setContentType("application/json;charset=UTF-8");
            json.writeValue(res.getOutputStream(), Map.of("code", e.code(), "message", e.getMessage(), "retryable", false));
            return;
        }
        try {
            chain.doFilter(req, res);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
