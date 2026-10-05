package com.example.ailab.data.persistence.po;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;

/** MyBatis 复杂投影的行映射；保留列顺序、NULL 和连接配置下的时间语义。 */
public final class SqlRowTypeHandler extends BaseTypeHandler<SqlRow> {
    @Override public SqlRow getNullableResult(ResultSet result, int column) throws SQLException { return row(result); }
    @Override public SqlRow getNullableResult(ResultSet result, String column) throws SQLException { return row(result); }
    private SqlRow row(ResultSet result) throws SQLException {
        var row = new SqlRow(); var metadata = result.getMetaData();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            int type = metadata.getColumnType(index);
            Object value = type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE
                    ? result.getTimestamp(index) : result.getObject(index);
            row.put(metadata.getColumnLabel(index), value);
        }
        return row;
    }
    @Override public void setNonNullParameter(PreparedStatement statement, int index, SqlRow value, JdbcType type) throws SQLException {
        throw new SQLException("查询投影不能作为标量参数绑定");
    }
    @Override public SqlRow getNullableResult(CallableStatement statement, int index) throws SQLException {
        throw new SQLException("查询投影不支持存储过程输出参数");
    }
}
