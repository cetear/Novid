package com.example.ailab.data.config;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

import java.sql.SQLException;

/**
 * 架构限定 MySQL 8.4；迁移前检查实际服务，避免在不兼容数据库上写入半套表结构。
 * 数据库产品／版本元信息使用 JDBC DatabaseMetaData；业务 SQL 统一由 Mapper 执行。
 */
@Component
public class VersionedDatabaseMigration implements FlywayMigrationStrategy {
    /**
     * 只读版本检查通过才执行 Flyway，失败不打印数据库地址或凭证。
     */
    @Override
    public void migrate(Flyway flyway) {
        try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
            var metadata = connection.getMetaData();
            if (!"MySQL".equalsIgnoreCase(metadata.getDatabaseProductName())
                    || metadata.getDatabaseMajorVersion() != 8 || metadata.getDatabaseMinorVersion() != 4) {
                throw new IllegalStateException("数据库版本不符合架构要求：需要 MySQL 8.4，未执行迁移");
            }
        } catch (SQLException error) {
            throw new IllegalStateException("数据库连接检查失败，未执行迁移", null);
        }
        // 检查连接已关闭，Flyway 独立管理正式迁移事务与锁。
        flyway.migrate();
    }
}
