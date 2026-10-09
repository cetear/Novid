package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.SqlRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface WorkflowRunMapper {
    List<SqlRow> binding(@Param("args") Object[] args);
    int bind(@Param("args") Object[] args);
}
