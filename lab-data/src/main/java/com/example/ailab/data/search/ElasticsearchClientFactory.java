package com.example.ailab.data.search;

import org.elasticsearch.client.RestClient;
import org.apache.http.HttpHost;
import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import co.elastic.clients.transport.TransportUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;

import org.apache.http.ssl.SSLContexts;
import org.apache.http.conn.ssl.TrustAllStrategy;

/**
 * ES 8.15 官方客户端统一连接工厂；索引和检索共享认证与局部 TLS 配置。
 */
public final class ElasticsearchClientFactory {
    /**
     * 工具类禁止实例化。
     */
    private ElasticsearchClientFactory() {
    }

    /**
     * Basic 认证独立于 TLS；trust-all 仅作用于本客户端，不修改 JVM 全局设置。
     */
    public static RestClient create(SearchProperties config) {
        URI endpoint = URI.create(config.endpoint());
        var builder = RestClient.builder(new HttpHost(endpoint.getHost(), endpoint.getPort(), endpoint.getScheme()));
        if (endpoint.getPath() != null && !endpoint.getPath().isEmpty() && !endpoint.getPath().equals("/")) {
            builder.setPathPrefix(endpoint.getPath());
        }
        if (!config.username().isEmpty()) {
            // Unicode 密码使用 UTF-8，认证头不进入日志或业务响应。
            String credentials = Base64.getEncoder().encodeToString(
                    (config.username() + ":" + config.password()).getBytes(StandardCharsets.UTF_8));
            builder.setDefaultHeaders(new Header[]{new BasicHeader("Authorization", "Basic " + credentials)});
        }
        if (config.trustAll()) {
            // 用户明确指定的测试开关同时跳过证书链与主机名校验；保留模型客户端的校验策略。
            var ssl = insecureSSLContext();
            builder.setHttpClientConfigCallback(http -> http.setSSLContext(ssl)
                    .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE));
        } else if (!config.caCertificate().isBlank()) {
            try {
                var ssl = TransportUtils.sslContextFromHttpCaCrt(new File(config.caCertificate()));
                builder.setHttpClientConfigCallback(http -> http.setSSLContext(ssl));
            } catch (IOException | RuntimeException error) {
                throw new IllegalArgumentException("无法加载 ES CA 证书，请检查 ES_CA_CERTIFICATE 文件", null);
            }
        }
        return builder.build();
    }

    /**
     * ES 8.15 所用 HTTP4 客户端的局部信任全部上下文，仅由明确开关调用。
     */
    private static SSLContext insecureSSLContext() {
        try {
            return SSLContexts.custom().loadTrustMaterial(null, TrustAllStrategy.INSTANCE).build();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("无法初始化 ES 测试 TLS 上下文", null);
        }
    }
}

