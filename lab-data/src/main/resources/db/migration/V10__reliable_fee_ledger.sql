-- S07可靠费用独立于ai_runs/ai_spans，没有观测级联外键或自动清理。
CREATE TABLE fee_scopes (
 scope_id CHAR(64) PRIMARY KEY, actor_user_id BIGINT NOT NULL,
 scope_kind VARCHAR(16) NOT NULL, resource_id VARCHAR(64) NOT NULL,
 currency CHAR(3) NOT NULL, limit_amount DECIMAL(24,8) NOT NULL,
 token_limit BIGINT NOT NULL, legacy_untracked_attempts INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY uk_fee_scope(actor_user_id,scope_kind,resource_id),
 FOREIGN KEY(actor_user_id) REFERENCES users(id),
 CHECK(limit_amount>0 AND token_limit>0 AND legacy_untracked_attempts>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE fee_attempts (
 operation_id CHAR(36) PRIMARY KEY, scope_id CHAR(64) NOT NULL, run_id CHAR(36) NOT NULL,
 model_id VARCHAR(128) NOT NULL, operation_type VARCHAR(16) NOT NULL,
 state VARCHAR(16) NOT NULL, outcome VARCHAR(64) NULL, simulated BOOLEAN NOT NULL,
 reserved_input BIGINT NOT NULL, reserved_output BIGINT NOT NULL,
 input_tokens BIGINT NULL, output_tokens BIGINT NULL, usage_source VARCHAR(24) NOT NULL,
 price_ref VARCHAR(64) NULL, price_version VARCHAR(64) NULL, currency CHAR(3) NOT NULL,
 price_unit VARCHAR(32) NULL, price_effective_at TIMESTAMP(6) NULL,
 input_rate DECIMAL(24,8) NULL, output_rate DECIMAL(24,8) NULL,
 reserved_amount DECIMAL(24,8) NULL, estimated_amount DECIMAL(24,8) NULL,
 request_hash CHAR(64) NOT NULL, response_hash CHAR(64) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 sent_at TIMESTAMP(6) NULL, completed_at TIMESTAMP(6) NULL,
 FOREIGN KEY(scope_id) REFERENCES fee_scopes(scope_id),
 INDEX idx_fee_run(run_id,scope_id), INDEX idx_fee_pending(state,created_at),
 INDEX idx_fee_aggregate(created_at,currency),
 CHECK(state IN ('RESERVED','SENDING','SETTLED','UNKNOWN','SIMULATED','RELEASED')),
 CHECK(reserved_input>=0 AND reserved_output>=0),
 CHECK(input_tokens IS NULL OR input_tokens>=0), CHECK(output_tokens IS NULL OR output_tokens>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
