-- 模型文本仅登记原始响应位置，不复制正文；随原载荷分批清理。
ALTER TABLE ai_model_call_payloads
 ADD COLUMN model_text_state VARCHAR(32) NOT NULL DEFAULT 'NOT_AVAILABLE',
 ADD COLUMN model_text_chars BIGINT NOT NULL DEFAULT 0;
CREATE TABLE ai_model_call_text_segments (
 payload_id CHAR(36) NOT NULL,
 segment_no INT NOT NULL, char_offset BIGINT NOT NULL, char_length INT NOT NULL,
 raw_start BIGINT NOT NULL, raw_end BIGINT NOT NULL, checksum CHAR(64) NOT NULL,
 PRIMARY KEY(payload_id,segment_no), INDEX idx_model_text_position(payload_id,char_offset),
 FOREIGN KEY(payload_id) REFERENCES ai_model_call_payloads(payload_id),
 CHECK(segment_no>=0 AND char_offset>=0),
 CHECK(char_length BETWEEN 1 AND 8192),
 CHECK((raw_start=-1 AND raw_end=-1 AND char_length=1) OR (raw_start>=0 AND raw_end>raw_start))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
