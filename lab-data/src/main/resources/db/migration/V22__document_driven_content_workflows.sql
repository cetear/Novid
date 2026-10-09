ALTER TABLE ai_tasks ADD COLUMN execution_seconds_limit INT NOT NULL DEFAULT 1200,
 ADD COLUMN model_repair_limit INT NOT NULL DEFAULT 1;
ALTER TABLE ai_tasks DROP CHECK chk_task_tool_budget, DROP CHECK chk_task_repair_budget,
 ADD CONSTRAINT chk_task_tool_budget CHECK(tool_calls BETWEEN 0 AND tool_call_limit),
 ADD CONSTRAINT chk_task_repair_budget CHECK(model_repairs BETWEEN 0 AND model_repair_limit),
 ADD CONSTRAINT chk_task_execution_limit CHECK(execution_seconds_limit BETWEEN 1200 AND 86400);
CREATE TABLE task_content_policies (
 task_id BIGINT PRIMARY KEY, policy_hash CHAR(64) NOT NULL, policy_json JSON NOT NULL,
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_content_runs (
 task_id BIGINT PRIMARY KEY, source_hash CHAR(64) NOT NULL, source_json JSON NOT NULL,
 plan_hash CHAR(64) NULL, plan_json JSON NULL,
 FOREIGN KEY(task_id) REFERENCES task_workflow_bindings(task_id),
 CHECK((plan_hash IS NULL AND plan_json IS NULL) OR (plan_hash IS NOT NULL AND plan_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE task_content_nodes (
 task_id BIGINT NOT NULL, node_id VARCHAR(64) NOT NULL, stage VARCHAR(32) NOT NULL,
 input_hash CHAR(64) NOT NULL, output_hash CHAR(64) NULL, output_json JSON NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', completed_at DATETIME(6) NULL,
 PRIMARY KEY(task_id,node_id), FOREIGN KEY(task_id) REFERENCES task_content_runs(task_id),
 CHECK(status IN ('PENDING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
