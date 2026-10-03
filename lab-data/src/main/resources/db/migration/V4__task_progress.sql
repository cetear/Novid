-- 任务进度只保存程序事实；保留 V1～V3 原文件和原有成功检查点。
ALTER TABLE ai_tasks
 ADD COLUMN started_at TIMESTAMP(6) NULL,
 ADD COLUMN heartbeat_at TIMESTAMP(6) NULL,
 ADD COLUMN updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6);

CREATE TABLE task_step_progress (
 task_id BIGINT NOT NULL, step_id VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
 started_at TIMESTAMP(6) NULL, completed_at TIMESTAMP(6) NULL, error_code VARCHAR(64) NULL,
 PRIMARY KEY(task_id,step_id), FOREIGN KEY(task_id) REFERENCES ai_tasks(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 历史任务补齐固定五步；旧时间无法追溯时保持 NULL，不捏造开始/完成时间。
INSERT INTO task_step_progress(task_id,step_id,status)
SELECT t.id,s.step_id,
 CASE
  WHEN c.step_id IS NOT NULL THEN 'SUCCEEDED'
  WHEN s.step_id='prepare' AND EXISTS(SELECT 1 FROM task_steps p WHERE p.task_id=t.id) THEN 'SUCCEEDED'
  WHEN s.step_id='publish' AND t.status IN ('SUCCEEDED','PARTIAL') THEN 'SUCCEEDED'
  WHEN t.status='CANCELLED' THEN 'CANCELLED'
  ELSE 'PENDING'
 END
FROM ai_tasks t
CROSS JOIN (SELECT 'prepare' AS step_id UNION ALL SELECT 'research' UNION ALL SELECT 'analysis'
 UNION ALL SELECT 'report' UNION ALL SELECT 'publish') s
LEFT JOIN task_steps c ON c.task_id=t.id AND c.step_id=s.step_id;
