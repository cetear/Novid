CREATE TABLE document_ingestions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, document_id BIGINT NOT NULL, document_version INT NOT NULL,
 processing_revision BIGINT NOT NULL, actor_user_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
 attempt INT NOT NULL DEFAULT 0, worker_id VARCHAR(64) NULL, lease_until TIMESTAMP(6) NULL,
 fencing_token BIGINT NOT NULL DEFAULT 0, expected_chunk_count INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) NULL, next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 config_hash CHAR(64) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(document_id,document_version,processing_revision),
 FOREIGN KEY(document_id,document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(actor_user_id) REFERENCES users(id), INDEX idx_ingestion_claim(status,next_attempt_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE document_sections (
 section_id VARCHAR(160) PRIMARY KEY, document_id BIGINT NOT NULL, document_version INT NOT NULL,
 processing_revision BIGINT NOT NULL, parent_section_id VARCHAR(160) NULL, ancestor_json JSON NOT NULL,
 heading_path TEXT NOT NULL, ordinal INT NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
 UNIQUE(document_id,document_version,processing_revision,ordinal)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE context_parents (
 parent_id VARCHAR(160) PRIMARY KEY, document_id BIGINT NOT NULL, document_version INT NOT NULL, processing_revision BIGINT NOT NULL,
 section_id VARCHAR(160) NOT NULL, ordinal INT NOT NULL, start_offset INT NOT NULL, end_offset INT NOT NULL,
 FOREIGN KEY(section_id) REFERENCES document_sections(section_id), INDEX idx_parent_document(document_id,document_version,processing_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE chunks (
 chunk_id VARCHAR(180) PRIMARY KEY, document_id BIGINT NOT NULL, document_version INT NOT NULL, processing_revision BIGINT NOT NULL,
 section_id VARCHAR(160) NOT NULL, parent_id VARCHAR(160) NOT NULL, index_in_section INT NOT NULL, index_in_parent INT NOT NULL,
 start_offset INT NOT NULL, end_offset INT NOT NULL, raw_text TEXT NOT NULL, embedding_text TEXT NOT NULL, chunk_hash CHAR(64) NOT NULL,
 FOREIGN KEY(section_id) REFERENCES document_sections(section_id), FOREIGN KEY(parent_id) REFERENCES context_parents(parent_id),
 UNIQUE(document_id,document_version,processing_revision,section_id,index_in_section),
 INDEX idx_chunks_parent(parent_id,index_in_parent)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
