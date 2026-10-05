-- S09最新审批规则：无音轨实测记0；NULL仍表示未测量。保留V1至V15原字节及历史事实。
ALTER TABLE media_shot_execution DROP CHECK media_shot_execution_chk_1,
 ADD CONSTRAINT chk_media_audio_duration_nonnegative CHECK(audio_duration_ms IS NULL OR audio_duration_ms>=0);
