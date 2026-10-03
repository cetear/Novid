package com.example.ailab.app;

import com.example.ailab.business.config.BusinessConfiguration;
import com.example.ailab.ai.config.AiConfiguration;
import com.example.ailab.data.config.DataConfiguration;
import com.example.ailab.data.search.SearchProperties;
import com.example.ailab.web.config.WebConfiguration;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;
import java.nio.file.Path;

/**
 * 唯一正式 Boot 装配入口，库模块保持普通 JAR。
 */
// ES 只通过 data 模块的 lab.search 配置装配，禁止 Boot 再创建默认 localhost 客户端。
@SpringBootApplication(exclude = {
        org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
        org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchRestClientAutoConfiguration.class,
        org.springframework.boot.autoconfigure.elasticsearch.ElasticsearchClientAutoConfiguration.class})
@Import({BusinessConfiguration.class, AiConfiguration.class, DataConfiguration.class, WebConfiguration.class})
@EnableConfigurationProperties({SearchProperties.class, BootstrapProperties.class})
@EnableScheduling
public class LabApplication {
    /**
     * 在 Spring 装配前读取工作目录中的 .env；全部持久化时间采用 UTC。
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        var application = new SpringApplication(LabApplication.class);
        // IDEA 的工作目录设为项目根目录；缺少 .env 时继续使用显式环境配置。
        LocalEnvironmentLoader.initialize(application, Path.of(".env"));
        application.run(args);
    }
}
