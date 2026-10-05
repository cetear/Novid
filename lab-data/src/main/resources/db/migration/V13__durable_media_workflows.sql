-- S09追加迁移；不修改V1～V12，普通报告预算仍六轮／十尝试。
ALTER TABLE ai_tasks ADD COLUMN media_phase VARCHAR(32) NULL,
 ADD COLUMN media_replans INT NOT NULL DEFAULT 0, ADD COLUMN media_reworks INT NOT NULL DEFAULT 0;
-- MySQL原自动唯一索引名为task_id；多类产物按稳定单元和版本去重。
ALTER TABLE artifacts DROP INDEX task_id, ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
 ADD COLUMN revision INT NOT NULL DEFAULT 1, ADD COLUMN unit_id VARCHAR(64) NOT NULL DEFAULT 'REPORT',
 ADD COLUMN preview_version INT NULL, ADD COLUMN storage_key VARCHAR(200) NULL,
 ADD COLUMN byte_size BIGINT NULL, ADD COLUMN media_operation_id CHAR(36) NULL,
 ADD UNIQUE KEY uk_artifact_unit(task_id,kind,revision,unit_id);
ALTER TABLE fee_attempts ADD COLUMN reserved_units BIGINT NULL, ADD COLUMN used_units BIGINT NULL;
CREATE TABLE media_plans (
 task_id BIGINT NOT NULL, plan_version INT NOT NULL, plan_hash CHAR(64) NOT NULL,
 plan_json JSON NOT NULL, model_id VARCHAR(128) NOT NULL, policy_version VARCHAR(128) NOT NULL,
 PRIMARY KEY(task_id,plan_version), FOREIGN KEY(task_id) REFERENCES ai_tasks(id), CHECK(plan_version BETWEEN 1 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_worker_results (
 task_id BIGINT NOT NULL, plan_version INT NOT NULL, step_id VARCHAR(32) NOT NULL,
 input_hash CHAR(64) NOT NULL, result_json JSON NOT NULL,
 PRIMARY KEY(task_id,plan_version,step_id), FOREIGN KEY(task_id,plan_version) REFERENCES media_plans(task_id,plan_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_catalog_items (
 item_id VARCHAR(64) NOT NULL, version INT NOT NULL, kind VARCHAR(32) NOT NULL,
 enabled BOOLEAN NOT NULL, item_json JSON NOT NULL, PRIMARY KEY(item_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE generation_previews (
 task_id BIGINT NOT NULL, preview_version INT NOT NULL, approval_id CHAR(36) NOT NULL,
 preview_hash CHAR(64) NOT NULL, config_hash CHAR(64) NOT NULL, status VARCHAR(32) NOT NULL,
 source_json JSON NOT NULL, preview_json JSON NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(task_id,preview_version), UNIQUE KEY uk_media_approval(approval_id),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id), CHECK(preview_version BETWEEN 1 AND 10)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_operations (
 operation_id CHAR(36) PRIMARY KEY, task_id BIGINT NOT NULL, preview_version INT NOT NULL,
 unit_id VARCHAR(64) NOT NULL, capability VARCHAR(32) NOT NULL, input_hash CHAR(64) NOT NULL,
 submission_json JSON NOT NULL, state VARCHAR(32) NOT NULL DEFAULT 'RESERVED',
 provider_job_id VARCHAR(256) NULL, provider_status VARCHAR(32) NULL,
 response_json JSON NULL, fee_scope_id CHAR(64) NOT NULL,
 poll_count INT NOT NULL DEFAULT 0, download_count INT NOT NULL DEFAULT 0,
 last_poll_at TIMESTAMP(6) NULL, next_poll_at TIMESTAMP(6) NULL, deadline TIMESTAMP(6) NULL,
 error_code VARCHAR(64) NULL, asset_id CHAR(36) NULL,
 UNIQUE KEY uk_media_operation(task_id,preview_version,unit_id,capability),
 FOREIGN KEY(task_id,preview_version) REFERENCES generation_previews(task_id,preview_version),
 FOREIGN KEY(operation_id) REFERENCES fee_attempts(operation_id),
 INDEX idx_media_poll(state,next_poll_at), CHECK(poll_count BETWEEN 0 AND 60), CHECK(download_count BETWEEN 0 AND 3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_attempts (
 operation_id CHAR(36) NOT NULL, action VARCHAR(16) NOT NULL, attempt INT NOT NULL,
 fencing_token BIGINT NOT NULL, started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(operation_id,action,attempt), FOREIGN KEY(operation_id) REFERENCES media_operations(operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_assets (
 asset_id CHAR(36) PRIMARY KEY, task_id BIGINT NOT NULL, unit_id VARCHAR(64) NOT NULL,
 operation_id CHAR(36) NULL, asset_json JSON NOT NULL, byte_size BIGINT NOT NULL,
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id), UNIQUE KEY uk_asset_operation(operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
