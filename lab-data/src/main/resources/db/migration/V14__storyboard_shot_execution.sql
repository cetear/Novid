-- S09分镜增量：保留V13原字节，不把旧整片操作自动转换成真实镜头。
ALTER TABLE ai_tasks ADD COLUMN media_deadline TIMESTAMP(6) NULL,
 ADD COLUMN media_remaining_seconds INT NOT NULL DEFAULT 1200;
CREATE TABLE media_preview_operations (
 task_id BIGINT NOT NULL, preview_version INT NOT NULL, operation_id CHAR(36) NOT NULL,
 PRIMARY KEY(task_id,preview_version,operation_id),
 FOREIGN KEY(task_id,preview_version) REFERENCES generation_previews(task_id,preview_version),
 FOREIGN KEY(operation_id) REFERENCES media_operations(operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO media_preview_operations SELECT task_id,preview_version,operation_id FROM media_operations;
CREATE TABLE media_shot_execution (
 task_id BIGINT NOT NULL, preview_version INT NOT NULL, shot_id VARCHAR(64) NOT NULL,
 approval_hash CHAR(64) NOT NULL, audio_duration_ms BIGINT NULL,
 provider_duration_seconds INT NULL, timeline_start_ms BIGINT NULL, timeline_end_ms BIGINT NULL,
 audio_asset_id CHAR(36) NULL, video_asset_id CHAR(36) NULL, rendered_asset_id CHAR(36) NULL,
 PRIMARY KEY(task_id,preview_version,shot_id),
 FOREIGN KEY(task_id,preview_version) REFERENCES generation_previews(task_id,preview_version),
 CHECK(audio_duration_ms IS NULL OR audio_duration_ms>0),
 CHECK(provider_duration_seconds IS NULL OR provider_duration_seconds BETWEEN 1 AND 90)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE media_local_renders (
 task_id BIGINT NOT NULL, input_hash CHAR(64) NOT NULL, unit_id VARCHAR(64) NOT NULL,
 attempt INT NOT NULL DEFAULT 0, asset_json JSON NULL,
 PRIMARY KEY(task_id,input_hash,unit_id), FOREIGN KEY(task_id) REFERENCES ai_tasks(id),
 CHECK(attempt BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- 当前适配仅单一后端凭证引用；数据库按提供方命名空间／原ID去重，未来多账户须追加真实账户命名空间。
ALTER TABLE media_operations ADD COLUMN provider_namespace VARCHAR(64) NOT NULL DEFAULT 'bigmodel',
 ADD UNIQUE KEY uk_media_provider_job(provider_namespace,provider_job_id);
