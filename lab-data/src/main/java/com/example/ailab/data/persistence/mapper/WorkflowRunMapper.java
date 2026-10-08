package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.SqlRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface WorkflowRunMapper {
    List<SqlRow> eligible(@Param("args") Object[] args);
    List<SqlRow> binding(@Param("args") Object[] args);
    int bind(@Param("args") Object[] args);
    List<SqlRow> actions(@Param("args") Object[] args);
    int start(@Param("args") Object[] args);
    int complete(@Param("args") Object[] args);
    int consumeRework(@Param("args") Object[] args);
    int executing(@Param("args") Object[] args);
}
