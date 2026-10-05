package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface IndexCleanupMapper {
    List<SqlRow> claimSystemControlSelect(@Param("args") Object[] args);
    int claimOutboxEventsWrite(@Param("args") Object[] args);
    List<SqlRow> claimOutboxEventsSelect(@Param("args") Object[] args);
    List<SqlRow> claimDocumentIngestionsSelect(@Param("args") Object[] args);
    int claimOutboxEventsWrite2(@Param("args") Object[] args);
    int finishOutboxEventsWrite(@Param("args") Object[] args);
    int failOutboxEventsWrite(@Param("args") Object[] args);
}
