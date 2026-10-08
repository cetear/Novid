-- 旧普通任务与媒体任务保留原额度；新学习工作流在创建时固化独立上限。
ALTER TABLE ai_tasks ADD COLUMN model_turn_limit INT NOT NULL DEFAULT 6,
 ADD COLUMN model_attempt_limit INT NOT NULL DEFAULT 10, ADD COLUMN tool_call_limit INT NOT NULL DEFAULT 8;
UPDATE ai_tasks SET model_turn_limit=24,model_attempt_limit=36,
 tool_call_limit=IF(task_type='NOTES_PPT',40,24) WHERE task_type IN ('NOTES_PPT','NOTES_VIDEO');
CREATE TABLE task_fixed_workflow_runs (
 task_id BIGINT NOT NULL PRIMARY KEY, baseline_hash CHAR(64) NOT NULL, baseline_json JSON NOT NULL,
 repair_hash CHAR(64) NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES task_workflow_bindings(task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_fixed_workflow_nodes (
 task_id BIGINT NOT NULL, node_id VARCHAR(64) NOT NULL, stage VARCHAR(32) NOT NULL,
 input_hash CHAR(64) NOT NULL, output_hash CHAR(64) NULL, output_json JSON NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 completed_at TIMESTAMP(6) NULL, PRIMARY KEY(task_id,node_id),
 FOREIGN KEY(task_id) REFERENCES task_fixed_workflow_runs(task_id), CHECK(status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
