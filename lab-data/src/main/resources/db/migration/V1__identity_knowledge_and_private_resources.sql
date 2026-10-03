-- MySQL 8.4 LTS；结构迁移不创建默认账号或写入密码。
CREATE TABLE system_control (id INT PRIMARY KEY, knowledge_epoch BIGINT NOT NULL DEFAULT 0);
INSERT INTO system_control(id) VALUES(1);
CREATE TABLE users (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 role VARCHAR(10) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT TRUE, permission_version BIGINT NOT NULL DEFAULT 1,
 password_change_required BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 CONSTRAINT chk_role CHECK(role IN ('ADMIN','USER')), INDEX idx_users_role(role,enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE auth_tokens (
 token_hash CHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, permission_version BIGINT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(user_id) REFERENCES users(id), INDEX idx_token_user(user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE knowledge_bases (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, owner_user_id BIGINT NOT NULL, name VARCHAR(200) NOT NULL,
 description VARCHAR(2000) NOT NULL DEFAULT '', enabled BOOLEAN NOT NULL DEFAULT TRUE, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 1, FOREIGN KEY(owner_user_id) REFERENCES users(id), INDEX idx_base_owner(owner_user_id,enabled,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE documents (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, knowledge_base_id BIGINT NOT NULL, owner_user_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, format VARCHAR(16) NOT NULL, current_version INT NOT NULL DEFAULT 1,
 deleted BOOLEAN NOT NULL DEFAULT FALSE, `generated` BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id), FOREIGN KEY(owner_user_id) REFERENCES users(id),
 INDEX idx_document_base(knowledge_base_id,deleted,current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE document_versions (
 document_id BIGINT NOT NULL, document_version INT NOT NULL, raw_text MEDIUMTEXT NOT NULL, checksum CHAR(64) NOT NULL,
 ingestion_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED', active_processing_revision BIGINT NULL,
 PRIMARY KEY(document_id,document_version), FOREIGN KEY(document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE source_dependencies (
 document_id BIGINT NOT NULL, document_version INT NOT NULL, source_base_id BIGINT NOT NULL,
 source_document_id BIGINT NOT NULL, source_document_version INT NOT NULL,
 PRIMARY KEY(document_id,document_version,source_document_id,source_document_version),
 FOREIGN KEY(document_id,document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(source_document_id,source_document_version) REFERENCES document_versions(document_id,document_version),
 FOREIGN KEY(source_base_id) REFERENCES knowledge_bases(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE request_deduplications (
 actor_user_id BIGINT NOT NULL, namespace VARCHAR(32) NOT NULL, request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, resource_id BIGINT NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(actor_user_id,namespace,request_key), FOREIGN KEY(actor_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE outbox_events (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, event_type VARCHAR(32) NOT NULL, resource_id BIGINT NOT NULL,
 resource_version BIGINT NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempt INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 lease_until TIMESTAMP(6) NULL, worker_id VARCHAR(64) NULL, fencing_token BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(event_type,resource_id,resource_version), INDEX idx_outbox_claim(status,next_attempt_at,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE approvals (
 approval_id CHAR(36) PRIMARY KEY, operation_id CHAR(36) NOT NULL UNIQUE, actor_user_id BIGINT NOT NULL,
 knowledge_base_id BIGINT NOT NULL, target_version BIGINT NOT NULL, title VARCHAR(200) NOT NULL, content MEDIUMTEXT NOT NULL,
 parameters_hash CHAR(64) NOT NULL, source_json JSON NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'WAITING', document_id BIGINT NULL, decided_at TIMESTAMP(6) NULL,
 FOREIGN KEY(actor_user_id) REFERENCES users(id), FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id),
 FOREIGN KEY(document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE operations (
 operation_id CHAR(36) PRIMARY KEY, actor_user_id BIGINT NOT NULL, parameters_hash CHAR(64) NOT NULL,
 document_id BIGINT NOT NULL, completed_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_user_id) REFERENCES users(id), FOREIGN KEY(document_id) REFERENCES documents(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE profile_memories (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, content VARCHAR(2000) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, deleted BOOLEAN NOT NULL DEFAULT FALSE,
 FOREIGN KEY(user_id) REFERENCES users(id), INDEX idx_memory_user(user_id,deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_runs (
 trace_id CHAR(36) PRIMARY KEY, actor_user_id BIGINT NOT NULL, status VARCHAR(32) NOT NULL,
 model_id VARCHAR(128) NOT NULL, attempts INT NOT NULL, mock BOOLEAN NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY(actor_user_id) REFERENCES users(id), INDEX idx_runs_owner(actor_user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE knowledge_access_audit (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, actor_user_id BIGINT NOT NULL, action VARCHAR(32) NOT NULL,
 resource_id BIGINT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(actor_user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
