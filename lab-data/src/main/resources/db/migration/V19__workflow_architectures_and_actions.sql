-- 已存在任务沿原Planner恢复，迁移后创建的任务才固定登记执行架构。
ALTER TABLE ai_tasks ADD COLUMN workflow_binding_eligible BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ai_tasks ALTER COLUMN workflow_binding_eligible SET DEFAULT TRUE;
CREATE TABLE task_workflow_bindings (
 task_id BIGINT NOT NULL PRIMARY KEY,
 binding_hash CHAR(64) NOT NULL,
 binding_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_workflow_actions (
 task_id BIGINT NOT NULL, action_sequence INT NOT NULL,
 action_hash CHAR(64) NOT NULL, action_json JSON NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at TIMESTAMP(6) NULL,
 PRIMARY KEY(task_id,action_sequence),
 FOREIGN KEY(task_id) REFERENCES task_workflow_bindings(task_id),
 CHECK(action_sequence BETWEEN 1 AND 12)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
