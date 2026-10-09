-- 保留历史任务、计划、检查点和产物；仅终止已经移除的工作流的未完成执行。
UPDATE ai_tasks SET status='CANCELLED',error_code='WORKFLOW_RETIRED',
 fencing_token=fencing_token+1,state_version=state_version+1,
 total_execution_seconds=total_execution_seconds+IF(claimed_at IS NULL,0,GREATEST(0,TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)))),
 claimed_at=NULL,worker_id=NULL,lease_until=NULL
 WHERE task_type IN ('FAQ','RESEARCH_REPORT') AND status IN ('QUEUED','RUNNING','PAUSED');
UPDATE task_step_progress p JOIN ai_tasks t ON t.id=p.task_id
 SET p.status='CANCELLED',p.error_code='WORKFLOW_RETIRED',p.completed_at=CURRENT_TIMESTAMP(6)
 WHERE t.task_type IN ('FAQ','RESEARCH_REPORT') AND t.status='CANCELLED' AND t.error_code='WORKFLOW_RETIRED'
 AND p.status IN ('PENDING','RUNNING','PAUSED');
