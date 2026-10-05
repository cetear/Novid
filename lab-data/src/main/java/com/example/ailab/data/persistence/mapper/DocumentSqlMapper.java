package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface DocumentSqlMapper {
    List<SqlRow> createRequestDeduplicationsSelect(@Param("args") Object[] args);
    List<SqlRow> createDocumentsSelect(@Param("args") Object[] args);
    int createRequestDeduplicationsWrite(@Param("args") Object[] args);
    int insertDocumentsInsert(@Param("insert") InsertCommand insert);
    int insertDocumentVersionsWrite(@Param("args") Object[] args);
    int insertDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> metadataRowsSelect(@Param("args") Object[] args);
    List<SqlRow> listRowsSelect(@Param("params") SqlParameters params);
    List<SqlRow> readRowsSelect(@Param("params") SqlParameters params);
    List<SqlRow> readDocumentVersionsSelect(@Param("args") Object[] args);
    int reviseDocumentsWrite(@Param("args") Object[] args);
    int reviseDocumentVersionsWrite(@Param("args") Object[] args);
    int reviseSourceDependenciesWrite(@Param("args") Object[] args);
    int reviseDocumentIngestionsWrite(@Param("args") Object[] args);
    int deleteDocumentsWrite(@Param("args") Object[] args);
    List<SqlRow> statisticsRowsSelect(@Param("params") SqlParameters params);
    List<SqlRow> walkDocumentVersionsSelect(@Param("args") Object[] args, @Param("regularUser") boolean regularUser);
    List<SqlRow> dependenciesSourceDependenciesSelect(@Param("args") Object[] args);
}
