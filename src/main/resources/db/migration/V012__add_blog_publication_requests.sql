-- Phase 7N: receipt reservation, post, Feed intents and completed ID must share one InnoDB transaction.
-- Retain successful receipts: no TTL, pruning, foreign-key cascade or historical backfill.
-- An incomplete receipt must never be committed by this application; do not steal/repair it on a retry.
-- CREATE IF NOT EXISTS preserves rows but does not validate/repair a different same-name structure.
CREATE TABLE IF NOT EXISTS tb_blog_publication_request (
    user_id BIGINT UNSIGNED NOT NULL,
    request_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    blog_id BIGINT UNSIGNED NULL COMMENT '同事务完成，重试返回原始发布ID，不代表当前可见性',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, request_key)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;
