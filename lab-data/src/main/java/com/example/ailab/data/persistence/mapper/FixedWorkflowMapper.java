package com.example.ailab.data.persistence.mapper;
import com.example.ailab.data.persistence.po.SqlRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;
public interface FixedWorkflowMapper {
    List<SqlRow> baseline(@Param("args") Object[] args);
    int bind(@Param("args") Object[] args);
    List<SqlRow> node(@Param("args") Object[] args);
    int begin(@Param("args") Object[] args);
    int complete(@Param("args") Object[] args);
    int stage(@Param("args") Object[] args);
    int repair(@Param("args") Object[] args);
}
