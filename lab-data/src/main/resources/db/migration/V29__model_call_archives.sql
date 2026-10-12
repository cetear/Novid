-- 私人调用全文独立于运行图节点限额及清理；正文分块不参与图查询。
CREATE TABLE ai_model_calls (
 call_id CHAR(36) NOT NULL PRIMARY KEY,
 trace_id CHAR(36) NOT NULL, span_id CHAR(36) NULL, business_node_id VARCHAR(128) NOT NULL,
 actor_user_id BIGINT NOT NULL, task_id BIGINT NOT NULL,
 source_json JSON NOT NULL, record_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 INDEX idx_model_calls_node(trace_id,business_node_id,created_at),
 INDEX idx_model_calls_owner(actor_user_id,trace_id), INDEX idx_model_calls_expiry(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_model_call_payloads (
 payload_id CHAR(36) NOT NULL PRIMARY KEY, call_id CHAR(36) NOT NULL,
 direction VARCHAR(6) NOT NULL, state VARCHAR(32) NOT NULL,
 ref_json JSON NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY uk_model_payload(call_id,direction), INDEX idx_model_payload_expiry(expires_at),
 FOREIGN KEY(call_id) REFERENCES ai_model_calls(call_id),
 CHECK(direction IN ('INPUT','OUTPUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_model_call_payload_chunks (
 payload_id CHAR(36) NOT NULL, chunk_no INT NOT NULL,
 char_offset BIGINT NOT NULL, char_length INT NOT NULL, raw_length INT NOT NULL,
 codec VARCHAR(4) NOT NULL, checksum CHAR(64) NOT NULL, content LONGBLOB NOT NULL,
 PRIMARY KEY(payload_id,chunk_no), FOREIGN KEY(payload_id) REFERENCES ai_model_call_payloads(payload_id),
 CHECK(codec IN ('RAW','GZIP')), CHECK(raw_length BETWEEN 0 AND 1048576)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
