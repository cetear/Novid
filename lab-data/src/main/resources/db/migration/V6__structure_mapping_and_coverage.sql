-- S02 只追加结构映射与逐页覆盖；旧批次保持 LEGACY，不伪造历史解析事实。
ALTER TABLE chunks
 ADD COLUMN block_type VARCHAR(24) NOT NULL DEFAULT 'LEGACY',
 ADD COLUMN block_id VARCHAR(180) NULL,
 ADD COLUMN part_index INT NOT NULL DEFAULT 0,
 ADD COLUMN source_map JSON NULL,
 ADD COLUMN token_count INT NULL,
 ADD COLUMN count_source VARCHAR(40) NULL;
ALTER TABLE document_ingestions
 ADD COLUMN parser_version VARCHAR(40) NULL,
 ADD COLUMN split_policy_version VARCHAR(40) NULL,
 ADD COLUMN mapping_version VARCHAR(40) NULL,
 ADD COLUMN tokenizer_ref VARCHAR(80) NULL,
 ADD COLUMN count_source VARCHAR(40) NULL;
CREATE TABLE task_document_coverage (
 task_id BIGINT NOT NULL, document_id BIGINT NOT NULL, document_version INT NOT NULL,
 processing_revision BIGINT NOT NULL, section_id VARCHAR(160) NOT NULL,
 completed_pages INT NOT NULL DEFAULT 0, read_start INT NOT NULL, read_end INT NOT NULL,
 remaining_start INT NOT NULL, remaining_end INT NOT NULL, complete BOOLEAN NOT NULL DEFAULT FALSE,
 count_source VARCHAR(40) NOT NULL,
 PRIMARY KEY(task_id,document_id), FOREIGN KEY(task_id) REFERENCES ai_tasks(id),
 FOREIGN KEY(document_id,document_version) REFERENCES document_versions(document_id,document_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_document_pages (
 task_id BIGINT NOT NULL, document_id BIGINT NOT NULL, page_index INT NOT NULL,
 page_json JSON NOT NULL, summary TEXT NOT NULL, source_json JSON NOT NULL,
 PRIMARY KEY(task_id,document_id,page_index),
 FOREIGN KEY(task_id,document_id) REFERENCES task_document_coverage(task_id,document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
