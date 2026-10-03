package com.example.ailab.web.security;

import com.example.ailab.business.application.AccountApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import jakarta.servlet.DispatcherType;

/**
 * 同源、无 Cookie、无服务端 HTTP session 的 Bearer API。
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    /**
     * CSRF 仅因不使用 Cookie 认证而关闭；不开放任意 CORS。
     */
    @Bean
    public SecurityFilterChain chain(HttpSecurity http, AccountApplicationService accounts, ObjectMapper json) throws Exception {
        return http.csrf(c -> c.disable()).cors(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable()).httpBasic(b -> b.disable()).requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a
                        // 容器内部的完成／错误派发不重新触发鉴权；首次 REQUEST 仍必须通过 Bearer。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/login", "/actuator/health").permitAll().anyRequest().authenticated())
                .addFilterBefore(new BearerTokenFilter(accounts, json), UsernamePasswordAuthenticationFilter.class).build();
    }
}
