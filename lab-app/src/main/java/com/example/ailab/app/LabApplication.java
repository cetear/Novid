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

/**
 * 唯一正式 Boot 装配入口，库模块保持普通 JAR。
 */
@SpringBootApplication(exclude = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class)
@Import({BusinessConfiguration.class, AiConfiguration.class, DataConfiguration.class, WebConfiguration.class})
@EnableConfigurationProperties({SearchProperties.class, BootstrapProperties.class})
@EnableScheduling
public class LabApplication {
    /**
     * 全部持久化时间采用 UTC，业务展示可另转用户时区。
     */
    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(LabApplication.class, args);
    }
}
