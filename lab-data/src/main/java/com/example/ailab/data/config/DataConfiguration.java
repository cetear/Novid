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
    /** 锁定读取每次访问数据库；保留复杂查询的空列及列顺序。 */
    @Bean
    public com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer databaseMapping() {
        return configuration -> {
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.getTypeHandlerRegistry().register(com.example.ailab.data.persistence.po.SqlRow.class,
                    new com.example.ailab.data.persistence.po.SqlRowTypeHandler());
            configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            configuration.setCallSettersOnNulls(true);
            configuration.setReturnInstanceForEmptyRow(true);
        };
    }
}
