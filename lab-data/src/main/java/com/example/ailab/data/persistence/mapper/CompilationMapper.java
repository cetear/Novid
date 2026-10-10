package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.SqlRow;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface CompilationMapper {
    List<SqlRow> bindingOrigin(@Param("args") Object[] args);
    List<SqlRow> bindings(@Param("args") Object[] args);
    int saveBinding(@Param("args") Object[] args);
    List<SqlRow> preparation(@Param("args") Object[] args);
    List<SqlRow> approval(@Param("args") Object[] args);
    int prepare(@Param("args") Object[] args);
    int saveOrigin(@Param("args") Object[] args);
    int saveSource(@Param("args") Object[] args);
    List<SqlRow> origin(@Param("args") Object[] args);
    List<SqlRow> sources(@Param("args") Object[] args);
    List<SqlRow> participations(@Param("args") Object[] args);
    List<SqlRow> sourceVersions(@Param("args") Object[] args);
    List<SqlRow> generated(@Param("args") Object[] args);
    int deleteDocument(@Param("args") Object[] args);
    int deleteBase(@Param("args") Object[] args);
}
