package com.example.ailab.contract.dto;

/** 公开的最新入库意图与当前激活代次；无 worker、路径、原始异常或凭证。旧批次版本字段可空。 */
public record IngestionMetadata(long documentId, int documentVersion, Long activeProcessingRevision,
                                long ingestionId, long processingRevision, String status, String errorCode,
                                int expectedChunkCount, String configHash, String parserVersion,
                                String splitPolicyVersion, String mappingVersion, String tokenizerRef,
                                String countSource) { }
