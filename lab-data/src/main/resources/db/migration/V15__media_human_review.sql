-- 新路由／原生音轨的技术就绪不等于本人验收；保留V13、V14原定义。
CREATE TABLE media_human_reviews (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT NOT NULL, preview_version INT NOT NULL,
 actor_id BIGINT NOT NULL, accepted BOOLEAN NOT NULL, note VARCHAR(2000) NOT NULL,
 approval_hash CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 FOREIGN KEY(task_id,preview_version) REFERENCES generation_previews(task_id,preview_version),
 FOREIGN KEY(actor_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
