package com.example.ailab.data.persistence.mapper;
import com.example.ailab.data.persistence.po.SqlRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface ContentWorkflowMapper {
    List<SqlRow> reading(@Param("args") Object[] args);
    int saveReading(@Param("args") Object[] args);
    int markReading(@Param("args") Object[] args);
    List<SqlRow> policy(@Param("args") Object[] args);
    int savePolicy(@Param("args") Object[] args);
    List<SqlRow> run(@Param("args") Object[] args);
    int bind(@Param("args") Object[] args);
    List<SqlRow> node(@Param("args") Object[] args);
    List<SqlRow> nodes(@Param("args") Object[] args);
    int begin(@Param("args") Object[] args);
    int complete(@Param("args") Object[] args);
    int accept(@Param("args") Object[] args);
    int progress(@Param("args") Object[] args);
    int stage(@Param("args") Object[] args);
    int coverage(@Param("args") Object[] args);
    List<SqlRow> packages(@Param("args") Object[] args);
    int savePackage(@Param("args") Object[] args);
    int beginPackage(@Param("args") Object[] args);
    int completePackage(@Param("args") Object[] args);
    int failPackage(@Param("args") Object[] args);
    int replacePackage(@Param("args") Object[] args);
}
