package com.example.ailab.data.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * 索引与向量空间是受控配置；凭证只用于服务端连接，不进入查询或响应。
 */
@ConfigurationProperties("lab.search")
public record SearchProperties(boolean enabled, String endpoint, String index, int dimensions,
                               String embeddingModelVersion, String username, String password, String caCertificate,
                               boolean trustAll) {
    /**
     * 校验地址和成对凭证，避免把密码塞进 URL 或产生不完整认证配置。
     */
    public SearchProperties {
        if (index == null || !index.matches("[a-z][a-z0-9_-]{1,100}") || dimensions < 1
                || dimensions > 4096 || embeddingModelVersion == null || embeddingModelVersion.isBlank()) {
            throw new IllegalArgumentException("搜索配置不合法");
        }
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        caCertificate = caCertificate == null ? "" : caCertificate;
        if (username.isEmpty() != password.isEmpty() || username.contains(":")
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ES 用户名和密码必须成对配置，用户名不能包含冒号或控制字符");
        }
        try {
            URI address = URI.create(endpoint);
            if (!java.util.Set.of("http", "https").contains(address.getScheme())
                    || address.getHost() == null || address.getUserInfo() != null
                    || address.getQuery() != null || address.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("ES 地址必须是无凭证的 HTTP 或 HTTPS 服务地址");
        }
    }

    /**
     * record 默认字符串会包含密码；显式脱敏，防止配置对象误记入日志。
     */
    @Override
    public String toString() {
        return "SearchProperties[enabled=" + enabled + ", index=" + index + ", dimensions="
                + dimensions + ", embeddingModelVersion=" + embeddingModelVersion
                + ", authentication=" + !username.isEmpty() + ", trustAll=" + trustAll + "]";
    }
}
