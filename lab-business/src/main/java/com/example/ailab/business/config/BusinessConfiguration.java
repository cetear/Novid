package com.example.ailab.business.config;

import org.springframework.context.annotation.*;

/**
 * 业务模块显式扫描，仅依赖公共契约。
 */
@Configuration
@ComponentScan("com.example.ailab.business")
public class BusinessConfiguration {
}
