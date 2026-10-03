package com.example.ailab.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始化只能显式启用，密码必须来自环境变量。
 */
@ConfigurationProperties("lab.bootstrap")
public record BootstrapProperties(boolean enabled, String username) {
}
