package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.*;
import org.apache.ibatis.annotations.Param;
import java.util.List;

/** 自定义 SQL 由 MyBatis 执行，参数绑定与事务使用统一数据源。 */
public interface IngestionMapper {
    List<SqlRow> claimSystemControlSelect(@Param("args") Object[] args);
    int claimDocumentIngestionsWrite(@Param("args") Object[] args);
    int claimIngestionBatchesWrite(@Param("args") Object[] args);
    int claimDocumentIngestionsWrite2(@Param("args") Object[] args);
    List<SqlRow> claimDocumentIngestionsSelect(@Param("args") Object[] args);
    int claimDocumentIngestionsWrite3(@Param("args") Object[] args);
    List<SqlRow> claimDocumentIngestionsSelect2(@Param("args") Object[] args);
    int renewDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> saveStructureDocumentIngestionsSelect(@Param("args") Object[] args);
    List<SqlRow> saveStructureChunksSelect(@Param("args") Object[] args);
    int saveStructureChunksWrite(@Param("args") Object[] args);
    int saveStructureContextParentsWrite(@Param("args") Object[] args);
    int saveStructureDocumentSectionsWrite(@Param("args") Object[] args);
    int saveStructureDocumentSectionsWrite2(@Param("args") Object[] args);
    int saveStructureContextParentsWrite2(@Param("args") Object[] args);
    int saveStructureChunksWrite2(@Param("args") Object[] args);
    int saveStructureDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> planDocumentIngestionsSelect(@Param("args") Object[] args);
    List<SqlRow> planIngestionBatchesSelect(@Param("args") Object[] args);
    int planIngestionBatchesWrite(@Param("args") Object[] args);
    int planDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> batchIngestionBatchesSelect(@Param("args") Object[] args);
    List<SqlRow> budgetDocumentIngestionsSelect(@Param("args") Object[] args);
    List<SqlRow> beginEmbeddingIngestionBatchesSelect(@Param("args") Object[] args);
    int beginEmbeddingDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> beginEmbeddingDocumentIngestionsSelect(@Param("args") Object[] args);
    int beginEmbeddingIngestionModelAttemptsWrite(@Param("args") Object[] args);
    int beginEmbeddingIngestionBatchesWrite(@Param("args") Object[] args);
    int completeEmbeddingIngestionModelAttemptsWrite(@Param("args") Object[] args);
    int completeEmbeddingDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> completeEmbeddingIngestionBatchesSelect(@Param("args") Object[] args);
    int completeEmbeddingIngestionBatchesWrite(@Param("args") Object[] args);
    int completeEmbeddingIngestionModelAttemptsWrite2(@Param("args") Object[] args);
    int completeEmbeddingDocumentIngestionsWrite2(@Param("args") Object[] args);
    int phaseDocumentIngestionsWrite(@Param("args") Object[] args);
    int indexedIngestionBatchesWrite(@Param("args") Object[] args);
    List<SqlRow> activateChunksSelect(@Param("args") Object[] args);
    List<SqlRow> activateIngestionBatchesSelect(@Param("args") Object[] args);
    int activateDocumentVersionsWrite(@Param("args") Object[] args);
    int activateDocumentIngestionsWrite(@Param("args") Object[] args);
    int activateOutboxEventsWrite(@Param("args") Object[] args);
    int failDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> failIngestionBatchesSelect(@Param("args") Object[] args);
    int failIngestionBatchesWrite(@Param("args") Object[] args);
    int failDocumentIngestionsWrite2(@Param("args") Object[] args);
    int failDocumentVersionsWrite(@Param("args") Object[] args);
    List<SqlRow> reprocessDocumentIngestionsSelect(@Param("args") Object[] args);
    int reprocessDocumentIngestionsWrite(@Param("args") Object[] args);
    int recoverDocumentIngestionsWrite(@Param("args") Object[] args);
    List<SqlRow> validDocumentIngestionsSelect(@Param("args") Object[] args);
}
