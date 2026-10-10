-- 展示关系与权限依赖独立；不从历史展开依赖自动回填直接关系。
CREATE TABLE compilation_save_preparations (
 approval_id CHAR(36) PRIMARY KEY, actor_user_id BIGINT NOT NULL,
 request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL, task_id BIGINT NOT NULL,
 target_base_id BIGINT NOT NULL, result_hash CHAR(64) NOT NULL,
 direct_source_json JSON NOT NULL, mapping_json JSON NOT NULL,
 UNIQUE KEY uq_compilation_save_request(actor_user_id,request_key),
 FOREIGN KEY(approval_id) REFERENCES approvals(approval_id),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id),
 FOREIGN KEY(target_base_id) REFERENCES knowledge_bases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE document_compilation_origins (
 document_id BIGINT NOT NULL, document_version INT NOT NULL, task_id BIGINT NOT NULL,
 result_hash CHAR(64) NOT NULL, content_hash CHAR(64) NOT NULL, mapping_json JSON NOT NULL,
 generated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(document_id,document_version),
 FOREIGN KEY(document_id,document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE document_compilation_sources (
 compiled_document_id BIGINT NOT NULL, compiled_document_version INT NOT NULL, compiled_base_id BIGINT NOT NULL,
 source_document_id BIGINT NOT NULL, source_document_version INT NOT NULL, source_base_id BIGINT NOT NULL,
 source_order INT NOT NULL,
 PRIMARY KEY(compiled_document_id,compiled_document_version,source_document_id,source_document_version),
 INDEX idx_compilation_source(source_document_id,compiled_document_id),
 INDEX idx_compilation_source_base(source_base_id), INDEX idx_compilation_output_base(compiled_base_id),
 FOREIGN KEY(compiled_document_id,compiled_document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(source_document_id,source_document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(compiled_base_id) REFERENCES knowledge_bases(id), FOREIGN KEY(source_base_id) REFERENCES knowledge_bases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
