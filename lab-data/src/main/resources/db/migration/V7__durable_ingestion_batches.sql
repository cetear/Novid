-- 只追加结构；存量成功批次不回填虚构的向量、用量和成功事实。
ALTER TABLE document_ingestions
 ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
 ADD COLUMN failure_stage VARCHAR(32) NULL,
 ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN execution_deadline TIMESTAMP(6) NULL,
 ADD COLUMN model_attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN reserved_input_tokens BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN actual_input_tokens BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN unknown_usage_attempts INT NOT NULL DEFAULT 0,
 ADD COLUMN peak_vector_items INT NOT NULL DEFAULT 0;
CREATE TABLE ingestion_batches (
 ingestion_id BIGINT NOT NULL, ordinal INT NOT NULL, start_index INT NOT NULL, item_count INT NOT NULL,
 batch_key CHAR(64) NOT NULL, input_hash CHAR(64) NOT NULL, input_tokens INT NOT NULL,
 state VARCHAR(32) NOT NULL DEFAULT 'PLANNED', vector_json JSON NULL, model_version VARCHAR(200) NULL,
 PRIMARY KEY(ingestion_id,ordinal), UNIQUE(batch_key),
 FOREIGN KEY(ingestion_id) REFERENCES document_ingestions(id),
 CHECK(item_count BETWEEN 1 AND 32), CHECK(input_tokens BETWEEN 1 AND 16000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ingestion_model_attempts (
 ingestion_id BIGINT NOT NULL, attempt_no INT NOT NULL, batch_ordinal INT NOT NULL,
 input_tokens INT NOT NULL, actual_input_tokens INT NULL, result VARCHAR(32) NOT NULL DEFAULT 'UNKNOWN',
 started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), completed_at TIMESTAMP(6) NULL,
 PRIMARY KEY(ingestion_id,attempt_no),
 FOREIGN KEY(ingestion_id,batch_ordinal) REFERENCES ingestion_batches(ingestion_id,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
