-- S01 私有会话；追加迁移，不改 V1～V4，不创建或清理用户资料。
CREATE TABLE sessions (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 user_id BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL,
 version BIGINT NOT NULL DEFAULT 1,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 scope_json JSON NOT NULL,
 next_seq BIGINT NOT NULL DEFAULT 1,
 context_floor_seq BIGINT NOT NULL DEFAULT 0,
 execution_id CHAR(36) NULL,
 lease_until TIMESTAMP(6) NULL,
 summary_content TEXT NULL,
 summary_covered_through_seq BIGINT NULL,
 summary_source_json JSON NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
 FOREIGN KEY(user_id) REFERENCES users(id),
 UNIQUE KEY uk_session_owner(id,user_id),
 INDEX idx_session_owner(user_id,deleted,id),
 CONSTRAINT chk_session_sequence CHECK(next_seq >= 1 AND context_floor_seq >= 0 AND context_floor_seq < next_seq),
 CONSTRAINT chk_session_lease CHECK((execution_id IS NULL AND lease_until IS NULL) OR (execution_id IS NOT NULL AND lease_until IS NOT NULL)),
 CONSTRAINT chk_session_summary CHECK((summary_content IS NULL AND summary_covered_through_seq IS NULL AND summary_source_json IS NULL)
   OR (summary_content IS NOT NULL AND summary_covered_through_seq IS NOT NULL AND summary_source_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 完整事件与模型窗口分开保存，唯一序号及复合外键落实会话归属。
CREATE TABLE messages (
 session_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 seq BIGINT NOT NULL,
 role VARCHAR(16) NOT NULL,
 status VARCHAR(16) NOT NULL,
 content MEDIUMTEXT NOT NULL,
 source_json JSON NOT NULL,
 source_reference_json JSON NOT NULL,
 scope_json JSON NOT NULL,
 tool_call_id VARCHAR(128) NULL,
 tool_name VARCHAR(64) NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(session_id,seq),
 FOREIGN KEY(session_id,user_id) REFERENCES sessions(id,user_id),
 INDEX idx_message_owner(user_id,session_id,seq),
 CONSTRAINT chk_message_seq CHECK(seq >= 1),
 CONSTRAINT chk_message_role CHECK(role IN ('USER','ASSISTANT','TOOL_REQUEST','TOOL_RESULT')),
 CONSTRAINT chk_message_status CHECK(status IN ('SUCCESS','NEEDS_INPUT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
