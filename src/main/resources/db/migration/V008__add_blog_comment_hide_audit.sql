-- First-level administrator hide audit. Apply before deploying Phase 7F.
-- No historical status/counter backfill, data deletion, or content snapshot.
-- DDL is not transactional; back up and review existing schema before applying.
-- An existing same-name table with a different shape is not automatically repaired.
CREATE TABLE IF NOT EXISTS tb_blog_comment_hide_audit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    comment_id BIGINT UNSIGNED NOT NULL,
    blog_id BIGINT UNSIGNED NOT NULL,
    operator_user_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(255) NOT NULL,
    from_status TINYINT UNSIGNED NOT NULL,
    to_status TINYINT UNSIGNED NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blog_comment_hide_audit_comment (comment_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci;
