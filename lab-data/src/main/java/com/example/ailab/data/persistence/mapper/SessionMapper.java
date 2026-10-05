package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface SessionMapper {
    List<SqlRow> createRequestDeduplicationsSelect(@Param("args") Object[] args);
    List<SqlRow> createSessionsSelect(@Param("args") Object[] args);
    int createSessionsInsert(@Param("insert") InsertCommand insert);
    int createRequestDeduplicationsWrite(@Param("args") Object[] args);
    List<SqlRow> listSessionsSelect(@Param("args") Object[] args);
    List<SqlRow> readSessionsSelect(@Param("args") Object[] args);
    List<SqlRow> deleteSessionsSelect(@Param("args") Object[] args);
    int deleteMessagesWrite(@Param("args") Object[] args);
    int deleteSessionsWrite(@Param("args") Object[] args);
    int beginSessionsWrite(@Param("args") Object[] args);
    int beginSessionsWrite2(@Param("args") Object[] args);
    int completeMessagesWrite(@Param("args") Object[] args);
    int completeSessionsWrite(@Param("args") Object[] args);
    int completeSessionsWrite2(@Param("args") Object[] args);
    int completeSessionsWrite3(@Param("args") Object[] args);
    List<SqlRow> completeRowsSelect(@Param("args") Object[] args);
    List<SqlRow> verifyDeliveryMessagesSelect(@Param("args") Object[] args);
    List<SqlRow> verifyHistoryDeliveryMessagesSelect(@Param("args") Object[] args);
    List<SqlRow> abortSessionsSelect(@Param("args") Object[] args);
    int abortSessionsWrite(@Param("args") Object[] args);
    List<SqlRow> loadSessionsSelect(@Param("args") Object[] args, @Param("lock") boolean lock);
    List<SqlRow> queryMessagesMessagesSelect(@Param("args") Object[] args, @Param("reverse") boolean reverse);
    int insertMessageMessagesWrite(@Param("args") Object[] args);
    List<SqlRow> walkSourcesDocumentsSelect(@Param("params") SqlParameters params);
    List<SqlRow> validateScopeKnowledgeBasesSelect(@Param("params") SqlParameters params);
    List<SqlRow> verifyReferenceDocumentsSelect(@Param("args") Object[] args);
    List<SqlRow> verifyReferenceDocumentSectionsSelect(@Param("args") Object[] args);
}
