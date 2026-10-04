-- S08只追加访问元数据和有界清理索引；旧审计未知字段不伪造。
ALTER TABLE knowledge_access_audit
 ADD COLUMN scope_mode VARCHAR(16) NULL,
 ADD COLUMN permission_version BIGINT NULL,
 ADD COLUMN knowledge_epoch BIGINT NULL,
 ADD COLUMN result_count INT NULL,
 ADD COLUMN outcome VARCHAR(24) NULL,
 ADD INDEX idx_audit_retention(created_at,id);
ALTER TABLE auth_tokens ADD INDEX idx_token_expiry(expires_at,token_hash);
ALTER TABLE request_deduplications ADD INDEX idx_dedup_expiry(expires_at);
ALTER TABLE document_ingestions ADD INDEX idx_ingestion_retention(status,created_at,id);
