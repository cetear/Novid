package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface DocumentContextMapper {
    List<SqlRow> sectionsDocumentSectionsSelect(@Param("args") Object[] args);
    List<SqlRow> chunksChunksSelect(@Param("args") Object[] args);
    List<SqlRow> sectionPageDocumentSectionsSelect(@Param("args") Object[] args, @Param("sectionId") String sectionId);
    List<SqlRow> ingestionDocumentIngestionsSelect(@Param("args") Object[] args);
    List<SqlRow> progressIngestionBatchesSelect(@Param("args") Object[] args);
    List<SqlRow> documentPageDocumentSectionsSelect(@Param("args") Object[] args);
    List<SqlRow> documentPageDocumentSectionsSelect2(@Param("args") Object[] args);
    List<SqlRow> expandChunksSelect(@Param("args") Object[] args);
    List<SqlRow> expandDocumentSectionsSelect(@Param("args") Object[] args);
    List<SqlRow> expandContextParentsSelect(@Param("args") Object[] args);
    List<SqlRow> expandChunksSelect2(@Param("args") Object[] args);
    List<SqlRow> expandChunksSelect3(@Param("args") Object[] args);
    List<SqlRow> expandChunksSelect4(@Param("args") Object[] args);
}
