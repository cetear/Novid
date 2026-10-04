package com.example.ailab.web.config;

import org.springframework.context.annotation.*;

/**
 * 接入模块配置，未扫描 AI 或 data 实现。
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@ComponentScan("com.example.ailab.web")
public class WebConfiguration implements org.springframework.web.servlet.config.annotation.WebMvcConfigurer {
    /** 私人运行数据禁缓存，不改变其他接口的既有HTTP契约。 */
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            /** 静态检查页没有私人数据，API响应包括失败均禁缓存。 */
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler) {
                response.setHeader("Cache-Control","no-store"); return true;
            }
        }).addPathPatterns("/api/v1/runs", "/api/v1/runs/**");
    }
}
