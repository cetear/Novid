-- 只追加状态与额度字段，保留全部来源、检查点、产物和费用历史。
ALTER TABLE ai_tasks ADD COLUMN retry_sequence INT NOT NULL DEFAULT 0,
 ADD COLUMN additional_fee_amount DECIMAL(24,8) NOT NULL DEFAULT 0,
 ADD COLUMN additional_token_limit BIGINT NOT NULL DEFAULT 0;
CREATE TABLE task_retry_events (
 task_id BIGINT NOT NULL, request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 request_hash CHAR(64) NOT NULL, retry_sequence INT NOT NULL, prior_error_code VARCHAR(64) NULL,
 prior_state_version BIGINT NOT NULL, prior_lease_recoveries INT NOT NULL, additions_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(task_id,request_key), UNIQUE(task_id,retry_sequence), FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 连带中断使用原包状态 FAILED 与独立错误码表示，续跑时转回 PENDING，不改变已接受包或内容节点约束。
