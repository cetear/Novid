package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface PrivateResourceMapper {
    List<SqlRow> listProfileMemoriesSelect(@Param("args") Object[] args);
    List<SqlRow> createProfileMemoriesSelect(@Param("args") Object[] args);
    int createProfileMemoriesInsert(@Param("insert") InsertCommand insert);
    int updateProfileMemoriesWrite(@Param("args") Object[] args);
    int deleteProfileMemoriesWrite(@Param("args") Object[] args);
    int resetSessionContextsSessionsWrite(@Param("args") Object[] args);
    int recordAiRunsWrite(@Param("args") Object[] args);
    int recordGraphAiSpansWrite(@Param("rows") List<Object[]> rows);
    List<SqlRow> graphAiSpansSelect(@Param("args") Object[] args);
    List<SqlRow> previousAiRunsSelect(@Param("args") Object[] args, @Param("task") boolean task);
    int purgeAiRunsWrite(@Param("args") Object[] args);
    int deliveredAiRunsWrite(@Param("args") Object[] args);
    List<SqlRow> listAiRunsSelect(@Param("args") Object[] args);
    List<SqlRow> readAiRunsSelect(@Param("args") Object[] args);
}
