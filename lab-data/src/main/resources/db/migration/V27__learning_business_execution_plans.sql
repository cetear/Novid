-- 新任务协议；保留历史数据但不在应用中解释旧学习执行协议。
CREATE TABLE task_content_packages (
 task_id BIGINT NOT NULL, package_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 parent_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL DEFAULT '',
 input_hash CHAR(64) NOT NULL, package_json JSON NOT NULL, parts_json JSON NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 result_hash CHAR(64) NULL, error_code VARCHAR(64) NULL,
 completed_at TIMESTAMP(6) NULL,
 PRIMARY KEY(task_id,package_id), INDEX idx_content_package_parent(task_id,parent_id),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id),
 CHECK(status IN ('PENDING','RUNNING','FAILED','REPLACED','ACCEPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
