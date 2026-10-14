-- Phase 7M: immutable recipient snapshot and fixed score, written with the post in one transaction.
-- No foreign keys/current visibility joins: withdrawal does not erase delivery intent.
-- Replay the same Redis member/score before conditional deletion; no lease is required for ZADD replay.
-- CREATE IF NOT EXISTS preserves rows but does not repair a differently defined same-name table.
-- No backfill/rebuild of historical Feed. Apply before application upgrade; never drop pending intent on rollback.
CREATE TABLE IF NOT EXISTS tb_blog_feed_delivery (
    blog_id BIGINT UNSIGNED NOT NULL,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    score_millis BIGINT UNSIGNED NOT NULL COMMENT '发布时采样的固定正毫秒分数，重试不得刷新',
    attempts INT UNSIGNED NOT NULL DEFAULT 0,
    last_error VARCHAR(64) NULL COMMENT '仅安全异常类名，不存异常文本或凭据',
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (blog_id, recipient_user_id),
    INDEX idx_blog_feed_delivery_due (next_attempt_at, blog_id, recipient_user_id)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;
