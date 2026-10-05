package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface ApprovalMapper {
    int prepareApprovalsWrite(@Param("args") Object[] args);
    int decideApprovalsWrite(@Param("args") Object[] args);
    List<SqlRow> decideApprovalsSelect(@Param("args") Object[] args);
    int decideSourceDependenciesWrite(@Param("args") Object[] args);
    int decideOperationsWrite(@Param("args") Object[] args);
    int decideApprovalsWrite2(@Param("args") Object[] args);
    List<SqlRow> loadApprovalsSelect(@Param("args") Object[] args, @Param("lock") boolean lock);
}
