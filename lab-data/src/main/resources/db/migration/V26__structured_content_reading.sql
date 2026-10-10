-- 创建时冻结；NULL 协议标志专供历史任务，禁止恢复时自动改组。
ALTER TABLE ai_tasks ADD COLUMN reading_protocol VARCHAR(40) NULL;
CREATE TABLE task_content_read_plans (
 task_id BIGINT PRIMARY KEY, plan_hash CHAR(64) NOT NULL, plan_json JSON NOT NULL,
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE document_compilation_section_bindings (
 document_id BIGINT NOT NULL, document_version INT NOT NULL, processing_revision BIGINT NOT NULL,
 chapter_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 logical_section_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 parsed_section_id VARCHAR(160) COLLATE utf8mb4_bin NOT NULL,
 start_offset INT NOT NULL, end_offset INT NOT NULL, mapping_hash CHAR(64) NOT NULL,
 PRIMARY KEY(document_id,document_version,processing_revision,logical_section_id,parsed_section_id),
 FOREIGN KEY(document_id,document_version) REFERENCES document_compilation_origins(document_id,document_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
