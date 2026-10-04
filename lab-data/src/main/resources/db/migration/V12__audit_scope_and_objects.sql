-- V11已在S08真实专项执行，保持原校验和；只追加有限的范围与对象ID元数据，无正文。
ALTER TABLE knowledge_access_audit
 ADD COLUMN scope_json JSON NULL,
 ADD COLUMN resource_ids_json JSON NULL;
