-- First-level visible comment cursor index. No counter/status backfill or historical data deletion.
-- Review existing schema and back up before applying: DDL may lock the table and is not transactional.
-- A same-name index with a different definition is not automatically repaired.
SET @campushub_schema = DATABASE();
SET @blog_comment_page_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema AND TABLE_NAME = 'tb_blog_comments'
      AND INDEX_NAME = 'idx_blog_comments_page'
);
SET @blog_comment_page_sql = IF(
    @blog_comment_page_exists = 0,
    'CREATE INDEX idx_blog_comments_page ON tb_blog_comments (blog_id, parent_id, answer_id, status, id)',
    'SELECT 1'
);
PREPARE blog_comment_page_statement FROM @blog_comment_page_sql;
EXECUTE blog_comment_page_statement;
DEALLOCATE PREPARE blog_comment_page_statement;
