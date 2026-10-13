-- Phase 7K: retain author-withdrawn posts, content, counters and descendants.
-- Apply before deploying this application; old applications ignore the visibility flag
-- and would expose withdrawn content. Do not roll back to them or DROP this column.
-- Repeat preserves existing 0/1 values. A differently defined same-name column
-- is not repaired automatically; inspect/back up the known schema before applying.
SET @campushub_schema = DATABASE();
SET @blog_withdrawn_exists = (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @campushub_schema AND TABLE_NAME = 'tb_blog' AND COLUMN_NAME = 'withdrawn'
);
SET @blog_withdrawn_sql = IF(
    @blog_withdrawn_exists = 1,
    'SELECT 1',
    'ALTER TABLE tb_blog ADD COLUMN withdrawn TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT ''0 visible, 1 author withdrawn'''
);
PREPARE blog_withdrawn_statement FROM @blog_withdrawn_sql;
EXECUTE blog_withdrawn_statement;
DEALLOCATE PREPARE blog_withdrawn_statement;
