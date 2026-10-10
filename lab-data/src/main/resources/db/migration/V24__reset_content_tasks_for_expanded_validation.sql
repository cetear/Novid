-- 内容规则统一升级，不再兼容旧策略、节点或预览。
-- 停止旧任务入口和Worker后迁移；仅清理三类资料任务，保留文档、视频及费用账本。
CREATE TEMPORARY TABLE reset_content_task_ids (id BIGINT PRIMARY KEY);
INSERT INTO reset_content_task_ids SELECT id FROM ai_tasks
 WHERE task_type IN ('QUIZ_GENERATION','KNOWLEDGE_COMPILATION','NOTES_PPT');
START TRANSACTION;
UPDATE ai_tasks t JOIN reset_content_task_ids r ON r.id=t.id
 SET t.status='CANCELLED',t.error_code='WORKFLOW_RETIRED',t.fencing_token=t.fencing_token+1,
 t.state_version=t.state_version+1,t.claimed_at=NULL,t.worker_id=NULL,t.lease_until=NULL;
DELETE c FROM media_attempts c JOIN media_operations o ON o.operation_id=c.operation_id JOIN reset_content_task_ids r ON r.id=o.task_id;
DELETE c FROM media_preview_operations c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_shot_execution c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_human_reviews c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM presentation_exports c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_local_renders c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_assets c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_operations c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM generation_previews c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_worker_results c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM media_plans c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_document_pages c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_document_coverage c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_nodes c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_runs c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_content_policies c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_workflow_bindings c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM task_step_progress c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM artifacts c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE c FROM ai_runs c JOIN reset_content_task_ids r ON r.id=c.task_id;
DELETE d FROM request_deduplications d JOIN reset_content_task_ids r ON CAST(r.id AS CHAR)=d.resource_id WHERE d.namespace='TASK_CREATE';
DELETE t FROM ai_tasks t JOIN reset_content_task_ids r ON r.id=t.id;
COMMIT;
DROP TEMPORARY TABLE reset_content_task_ids;
