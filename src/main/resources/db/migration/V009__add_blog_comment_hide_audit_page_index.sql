-- Candidate per-post hide audit cursor index; performance has not been measured.
-- V008 must already exist. Back up/review before DDL: table locks and implicit commits are possible.
-- A same-name index with a different definition is not repaired automatically.
-- No historical backfill, content snapshot, status/counter changes or data deletion.
SET @campushub_schema = DATABASE();
SET @blog_comment_hide_audit_page_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema AND TABLE_NAME = 'tb_blog_comment_hide_audit'
      AND INDEX_NAME = 'idx_blog_comment_hide_audit_page'
);
SET @blog_comment_hide_audit_page_sql = IF(
    @blog_comment_hide_audit_page_exists = 0,
    'CREATE INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit (blog_id, id)',
    'SELECT 1'
);
PREPARE blog_comment_hide_audit_page_statement FROM @blog_comment_hide_audit_page_sql;
EXECUTE blog_comment_hide_audit_page_statement;
DEALLOCATE PREPARE blog_comment_hide_audit_page_statement;
