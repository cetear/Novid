-- 三类资料工作流不再读取旧请求、检查点或预览；升级时清除受影响的任务数据。
-- 原始文档、知识库、视频任务及不可抹除的费用账本不属于清理范围。
-- 部署先停止任务入口和Worker，再执行本迁移并启动当前版本。
CREATE TEMPORARY TABLE retired_content_task_ids (id BIGINT PRIMARY KEY);
INSERT INTO retired_content_task_ids SELECT id FROM ai_tasks
 WHERE task_type IN ('QUIZ_GENERATION','KNOWLEDGE_COMPILATION','NOTES_PPT');
UPDATE ai_tasks t JOIN retired_content_task_ids r ON r.id=t.id
 SET t.status='CANCELLED',t.error_code='WORKFLOW_RETIRED',t.fencing_token=t.fencing_token+1,
 t.state_version=t.state_version+1,t.claimed_at=NULL,t.worker_id=NULL,t.lease_until=NULL;
DELETE c FROM media_attempts c JOIN media_operations o ON o.operation_id=c.operation_id JOIN retired_content_task_ids r ON r.id=o.task_id;
DELETE c FROM media_preview_operations c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_shot_execution c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_human_reviews c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM presentation_exports c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_local_renders c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_assets c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_operations c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM generation_previews c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_worker_results c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_plans c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_document_pages c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_document_coverage c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_nodes c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_runs c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_policies c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_fixed_workflow_nodes c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_fixed_workflow_runs c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_workflow_actions c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_execution_bindings c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_workflow_bindings c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_steps c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_plans c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_step_progress c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE c FROM artifacts c JOIN retired_content_task_ids r ON r.id=c.task_id;
DELETE d FROM request_deduplications d JOIN retired_content_task_ids r ON CAST(r.id AS CHAR)=d.resource_id WHERE d.namespace='TASK_CREATE';
DELETE t FROM ai_tasks t JOIN retired_content_task_ids r ON r.id=t.id;
DROP TEMPORARY TABLE retired_content_task_ids;
-- 这些表和标记已经没有任何生产执行者或查询者。
DROP TABLE task_fixed_workflow_nodes;
DROP TABLE task_fixed_workflow_runs;
DROP TABLE task_workflow_actions;
DROP TABLE task_execution_bindings;
DROP TABLE task_steps;
DROP TABLE task_plans;
ALTER TABLE ai_tasks DROP COLUMN skill_binding_eligible,
 DROP COLUMN workflow_binding_eligible, DROP COLUMN workflow_version;
