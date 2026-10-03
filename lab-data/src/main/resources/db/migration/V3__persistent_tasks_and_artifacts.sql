CREATE TABLE ai_tasks (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, requester_user_id BIGINT NOT NULL, task_type VARCHAR(32) NOT NULL,
 request_json JSON NOT NULL, request_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
 state_version BIGINT NOT NULL DEFAULT 1, model_attempts INT NOT NULL DEFAULT 0, model_turns INT NOT NULL DEFAULT 0, completed_steps INT NOT NULL DEFAULT 0,
 worker_id VARCHAR(64) NULL, lease_until TIMESTAMP(6) NULL, fencing_token BIGINT NOT NULL DEFAULT 0,
 claimed_at TIMESTAMP(6) NULL, total_execution_seconds BIGINT NOT NULL DEFAULT 0, attempt INT NOT NULL DEFAULT 0,
 error_code VARCHAR(64) NULL, artifact_id BIGINT NULL, workflow_version VARCHAR(32) NOT NULL DEFAULT 'report-v1',
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(requester_user_id) REFERENCES users(id), INDEX idx_task_claim(status,lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_steps (
 task_id BIGINT NOT NULL, step_id VARCHAR(32) NOT NULL, content MEDIUMTEXT NOT NULL, source_json JSON NOT NULL,
 partial BOOLEAN NOT NULL, PRIMARY KEY(task_id,step_id), FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE artifacts (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, requester_user_id BIGINT NOT NULL, task_id BIGINT NOT NULL,
 filename VARCHAR(100) NOT NULL, mime VARCHAR(100) NOT NULL, content MEDIUMTEXT NOT NULL, source_json JSON NOT NULL,
 checksum CHAR(64) NOT NULL, published BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(task_id),
 FOREIGN KEY(requester_user_id) REFERENCES users(id), FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
