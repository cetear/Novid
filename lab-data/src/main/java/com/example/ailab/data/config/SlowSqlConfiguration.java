package com.example.ailab.data.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 由 DataConfiguration 的组件扫描装配，MyBatis-Plus 自动配置收集 Interceptor Bean。 */
@Configuration(proxyBeanMethods = false)
public class SlowSqlConfiguration {
    @Bean
    @ConditionalOnProperty(name = "lab.database.slow-sql.enabled", havingValue = "true", matchIfMissing = true)
    public SlowSqlInterceptor slowSqlInterceptor(
            @Value("${lab.database.slow-sql.threshold-ms:1000}") long thresholdMillis,
            @Value("${lab.database.slow-sql.max-sql-length:4096}") int maxSqlLength) {
        return new SlowSqlInterceptor(thresholdMillis, maxSqlLength);
    }
}
