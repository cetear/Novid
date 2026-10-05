package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface SqlSupportMapper {
    List<SqlRow> actorSystemControlSelect(@Param("args") Object[] args);
    int eventOutboxEventsWrite(@Param("args") Object[] args);
    List<SqlRow> knowledgeEpochSystemControlSelect(@Param("args") Object[] args);
    List<SqlRow> cacheScopeStateKnowledgeBasesSelect(@Param("params") SqlParameters params);
    int auditKnowledgeAccessAuditWrite(@Param("args") Object[] args);
    int changedSystemControlWrite(@Param("args") Object[] args);
}
