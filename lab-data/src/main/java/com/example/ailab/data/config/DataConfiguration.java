package com.example.ailab.data.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.*;

/**
 * 数据模块配置；PO、Mapper、迁移和搜索实现只在此模块。
 */
@Configuration
@ComponentScan("com.example.ailab.data")
@MapperScan("com.example.ailab.data.persistence.mapper")
public class DataConfiguration {
}
