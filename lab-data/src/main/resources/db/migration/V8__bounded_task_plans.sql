-- S05追加：工具与结构修复是可靠预算，恢复不能重置；不修改V1～V7。
ALTER TABLE ai_tasks
 ADD COLUMN tool_calls INT NULL DEFAULT NULL,
 ADD COLUMN model_repairs INT NULL DEFAULT NULL,
 ADD CONSTRAINT chk_task_tool_budget CHECK(tool_calls BETWEEN 0 AND 8),
 ADD CONSTRAINT chk_task_repair_budget CHECK(model_repairs BETWEEN 0 AND 1);

-- 旧任务的历史工具／修复用量未知，保留NULL而非补零；不改变已有任务状态和正文。
-- 新任务创建时显式登记0；旧未完成任务若要开始新工具／修复会保守拒绝。

-- 一个任务只有一个已校验计划，短事务与原租约fencing共用；不保存内部提示或凭证。
CREATE TABLE task_plans (
 task_id BIGINT PRIMARY KEY,
 plan_version VARCHAR(32) NOT NULL,
 plan_hash CHAR(64) NOT NULL,
 plan_json JSON NOT NULL,
 agent_version VARCHAR(32) NOT NULL,
 model_id VARCHAR(64) NOT NULL,
 policy_version VARCHAR(128) NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
