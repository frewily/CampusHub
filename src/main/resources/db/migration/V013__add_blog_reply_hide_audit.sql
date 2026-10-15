-- Phase 7O: direct-reply ADMIN hide audit, separate from the V008 root-only audit contract.
-- State, counter and this receipt must be written in one InnoDB transaction.
-- No content snapshot, foreign-key cascade, historical backfill or automatic retention cleanup.
-- CREATE IF NOT EXISTS preserves data; it does not validate/repair a wrong same-name table.
CREATE TABLE IF NOT EXISTS tb_blog_reply_hide_audit (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    reply_id BIGINT UNSIGNED NOT NULL,
    blog_id BIGINT UNSIGNED NOT NULL,
    root_comment_id BIGINT UNSIGNED NOT NULL,
    operator_user_id BIGINT UNSIGNED NOT NULL,
    reason VARCHAR(255) NOT NULL,
    from_status TINYINT UNSIGNED NOT NULL,
    to_status TINYINT UNSIGNED NOT NULL,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blog_reply_hide_audit_reply (reply_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET=utf8mb4 COLLATE=utf8mb4_general_ci;
