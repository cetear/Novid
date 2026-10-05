package com.example.ailab.data.persistence.po;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;

/** MyBatis 复杂查询的内部投影；不持有连接、游标或其他数据库资源。 */
public final class SqlRow extends LinkedHashMap<String, Object> {
    public Object value(String column) {
        if (!containsKey(column)) throw new IllegalArgumentException("查询结果缺少列：" + column);
        return get(column);
    }
    public Object value(int column) {
        if (column < 1 || column > size()) throw new IllegalArgumentException("查询列下标越界");
        var values = values().iterator();
        for (int i = 1; i < column; i++) values.next();
        return values.next();
    }
    public String string(String column) { return text(value(column)); }
    public String string(int column) { return text(value(column)); }
    public long longValue(String column) { return number(value(column)).longValue(); }
    public long longValue(int column) { return number(value(column)).longValue(); }
    public int intValue(String column) { return number(value(column)).intValue(); }
    public int intValue(int column) { return number(value(column)).intValue(); }
    public boolean booleanValue(String column) {
        Object value = value(column);
        return value instanceof Boolean flag ? flag : value != null && number(value).intValue() != 0;
    }
    public BigDecimal decimal(String column) { return (BigDecimal) value(column); }
    public Timestamp timestamp(String column) {
        Object value = value(column);
        return value instanceof LocalDateTime time ? Timestamp.valueOf(time) : (Timestamp) value;
    }
    public Timestamp timestamp(int column) {
        Object value = value(column);
        return value instanceof LocalDateTime time ? Timestamp.valueOf(time) : (Timestamp) value;
    }
    private static String text(Object value) { return value == null ? null : value.toString(); }
    private static Number number(Object value) { return value == null ? 0 : (Number) value; }
}
