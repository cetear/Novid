package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface FeeMapper {
    int completeMediaOutcome(@Param("args") Object[] args);
    List<SqlRow> reserveAiTasksSelect(@Param("args") Object[] args);
    List<SqlRow> reserveFeeScopesSelect(@Param("args") Object[] args);
    int reserveFeeScopesWrite(@Param("args") Object[] args);
    List<SqlRow> reserveFeeScopesSelect2(@Param("args") Object[] args);
    List<SqlRow> reserveFeeAttemptsSelect(@Param("args") Object[] args);
    List<SqlRow> reserveFeeAttemptsSelect2(@Param("args") Object[] args);
    List<SqlRow> reserveFeeAttemptsSelect3(@Param("args") Object[] args);
    int reserveFeeAttemptsWrite(@Param("args") Object[] args);
    int reserveFeeAttemptsWrite2(@Param("args") Object[] args);
    int sendingFeeAttemptsWrite(@Param("args") Object[] args);
    int releaseFeeAttemptsWrite(@Param("args") Object[] args);
    List<SqlRow> completeFeeAttemptsSelect(@Param("args") Object[] args);
    int completeFeeAttemptsWrite(@Param("args") Object[] args);
    List<SqlRow> completeMediaFeeAttemptsSelect(@Param("args") Object[] args);
    int completeMediaFeeAttemptsWrite(@Param("args") Object[] args);
    List<SqlRow> mediaTaskAiTasksSelect(@Param("args") Object[] args);
    List<SqlRow> lockFeeScopesSelect(@Param("args") Object[] args);
    List<SqlRow> summaryFeeAttemptsSelect(@Param("args") Object[] args);
    List<SqlRow> summaryAiRunsSelect(@Param("args") Object[] args);
    List<SqlRow> summaryFeeAttemptsSelect2(@Param("args") Object[] args);
    List<SqlRow> summaryFeeScopesSelect(@Param("args") Object[] args);
    List<SqlRow> totalsFeeScopesSelect(@Param("args") Object[] args);
    List<SqlRow> totalsFeeAttemptsSelect(@Param("args") Object[] args);
    List<SqlRow> totalsFeeAttemptsSelect2(@Param("args") Object[] args);
    List<SqlRow> legacyAiTasksSelect(@Param("args") Object[] args, @Param("task") boolean task);
    List<SqlRow> aggregateFeeAttemptsSelect(@Param("args") Object[] args);
    List<SqlRow> markUnknownBeforeFeeAttemptsSelect(@Param("args") Object[] args);
    int markUnknownBeforeFeeAttemptsWrite(@Param("args") Object[] args);
}
