-- S10只追加本地导出事实；V1～V16和外部操作／费用事实原样保留。
CREATE TABLE presentation_exports (
 task_id BIGINT NOT NULL, preview_version INT NOT NULL, input_hash CHAR(64) NOT NULL,
 attempt INT NOT NULL DEFAULT 0, bundle_json JSON NULL,
 PRIMARY KEY(task_id,preview_version),
 FOREIGN KEY(task_id,preview_version) REFERENCES generation_previews(task_id,preview_version),
 CHECK(attempt BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
