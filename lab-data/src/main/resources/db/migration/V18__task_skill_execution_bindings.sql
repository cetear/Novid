-- Skill内容与工具契约在首次规划前固定；旧任务没有记录，保留历史兼容路径。
ALTER TABLE ai_tasks ADD COLUMN skill_binding_eligible BOOLEAN NOT NULL DEFAULT FALSE;
-- 已存在任务保持FALSE，迁移后的新任务默认参与Skill绑定。
ALTER TABLE ai_tasks ALTER COLUMN skill_binding_eligible SET DEFAULT TRUE;
CREATE TABLE task_execution_bindings (
 task_id BIGINT NOT NULL PRIMARY KEY,
 binding_hash CHAR(64) NOT NULL,
 binding_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
