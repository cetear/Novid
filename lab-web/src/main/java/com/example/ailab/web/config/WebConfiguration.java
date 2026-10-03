package com.example.ailab.web.config;

import org.springframework.context.annotation.*;

/**
 * 接入模块配置，未扫描 AI 或 data 实现。
 */
@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
@ComponentScan("com.example.ailab.web")
public class WebConfiguration {
}
