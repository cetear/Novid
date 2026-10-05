package com.example.ailab.data.persistence.po;

import java.util.LinkedHashMap;

/** 动态权限查询的命名参数，值统一经 MyBatis 参数绑定。 */
public final class SqlParameters extends LinkedHashMap<String, Object> {
    public SqlParameters addValue(String name, Object value) { put(name, value); return this; }
}
