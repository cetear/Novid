-- S06仅追加脱敏排错事实；V1～V8和任务可靠预算保持原值。
ALTER TABLE ai_runs
 ADD COLUMN ended_at TIMESTAMP(6) NULL,
 ADD COLUMN session_id BIGINT NULL,
 ADD COLUMN task_id BIGINT NULL,
 ADD COLUMN ingestion_id BIGINT NULL,
 ADD COLUMN previous_trace_id CHAR(36) NULL,
 ADD COLUMN incomplete BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN telemetry_dropped BOOLEAN NOT NULL DEFAULT FALSE,
 ADD COLUMN node_count INT NOT NULL DEFAULT 0,
 ADD COLUMN first_deliverable_at TIMESTAMP(6) NULL,
 ADD INDEX idx_runs_task(actor_user_id,task_id,created_at),
 ADD INDEX idx_runs_ingestion(actor_user_id,ingestion_id,created_at),
 ADD INDEX idx_runs_retention(created_at);
-- 父节点可因限量丢失，故不设父外键；run级级联仅清理观测。
CREATE TABLE ai_spans (
 trace_id CHAR(36) NOT NULL,
 span_id CHAR(36) NOT NULL,
 parent_span_id CHAR(36) NULL,
 sequence_no INT NOT NULL,
 node_type VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL,
 started_at TIMESTAMP(6) NOT NULL,
 ended_at TIMESTAMP(6) NULL,
 node_json JSON NOT NULL,
 PRIMARY KEY(trace_id,span_id),
 UNIQUE KEY uk_span_sequence(trace_id,sequence_no),
 FOREIGN KEY(trace_id) REFERENCES ai_runs(trace_id) ON DELETE CASCADE,
 CHECK(sequence_no BETWEEN 1 AND 2000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
