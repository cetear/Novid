package com.example.ailab.data.search;

import org.elasticsearch.client.RestClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 业务和 Actuator 共享同一受控客户端，地址、Basic 凭证及 TLS 策略只有一个来源。 */
@Configuration(proxyBeanMethods = false)
public class ElasticsearchConnectionConfiguration {
    /** 搜索关闭时不创建连接；Spring 统一关闭共享客户端，仓库不能提前释放它。 */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "lab.search.enabled", havingValue = "true")
    public RestClient knowledgeRestClient(SearchProperties properties) {
        return ElasticsearchClientFactory.create(properties);
    }
}
