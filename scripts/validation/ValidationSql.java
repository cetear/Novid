package com.example.ailab.app;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.data.repository.SqlSupport;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.Statement;
import java.sql.SQLException;

/** 仅供原生验收脚本准备、核对和清理合成夹具，不进入生产数据库访问层。 */
public final class ValidationSql {
    final JdbcTemplate jdbc;
    private final SqlSupport support;

    private ValidationSql(ApplicationContext context) {
        jdbc = context.getBean(JdbcTemplate.class);
        support = context.getBean(SqlSupport.class);
    }
    ValidationSql(JdbcTemplate jdbc) { this.jdbc = jdbc; this.support = null; }
    public static ValidationSql from(ApplicationContext context) { return new ValidationSql(context); }
    SqlSupport support() {
        if (support == null) throw new IllegalStateException("权限验收需要正式 MyBatis 配置");
        return support;
    }
    void actor(UserContext actor, boolean lock) { support().actor(actor, lock); }
    void owner(UserContext actor, long id, boolean enabled) { support().owner(actor, id, enabled); }
    void changed() { support().changed(); }
    public long insert(String statement, Object... args) {
        var keys = new GeneratedKeyHolder();
        int affected = jdbc.update(connection -> {
            var prepared = connection.prepareStatement(statement, Statement.RETURN_GENERATED_KEYS);
            try {
                for (int i = 0; i < args.length; i++) prepared.setObject(i + 1, args[i]);
                return prepared;
            } catch (SQLException | RuntimeException | Error failure) {
                try { prepared.close(); } catch (SQLException closing) { failure.addSuppressed(closing); }
                throw failure;
            }
        }, keys);
        if (affected != 1 || keys.getKey() == null) throw new IllegalStateException("夹具新增未返回主键");
        return keys.getKey().longValue();
    }
}
