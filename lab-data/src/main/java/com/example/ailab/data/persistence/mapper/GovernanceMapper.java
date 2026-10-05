package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface GovernanceMapper {
    List<SqlRow> auditsKnowledgeAccessAuditSelect(@Param("args") Object[] args);
    List<SqlRow> metricsAiRunsSelect(@Param("args") Object[] args);
    List<SqlRow> metricsKnowledgeAccessAuditSelect(@Param("args") Object[] args);
    List<SqlRow> purgeSystemControlSelect(@Param("args") Object[] args);
    int purgeAuthTokensWrite(@Param("args") Object[] args);
    List<SqlRow> purgeRequestDeduplicationsSelect(@Param("args") Object[] args);
    int purgeRequestDeduplicationsWrite(@Param("args") Object[] args);
    int purgeKnowledgeAccessAuditWrite(@Param("args") Object[] args);
    List<SqlRow> pruneStructureDocumentIngestionsSelect(@Param("args") Object[] args);
    int pruneStructureChunksWrite(@Param("args") Object[] args);
    int pruneStructureContextParentsWrite(@Param("args") Object[] args);
    int pruneStructureDocumentSectionsWrite(@Param("args") Object[] args);
    List<SqlRow> queuedTaskCount();
    List<SqlRow> pendingEventCount();
}
