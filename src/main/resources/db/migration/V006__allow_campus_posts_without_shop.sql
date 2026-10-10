-- Phase 7A: a campus post may omit its store association.
-- Preserve the legacy signed BIGINT and all existing rows. Apply before deploying
-- the application that accepts NULL; old code can still publish positive store IDs.
-- Only the known tb_blog schema is supported. Back up and review historical data first.
SET @campushub_schema = DATABASE();
SET @blog_shop_nullable = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_blog'
      AND COLUMN_NAME = 'shop_id'
      AND IS_NULLABLE = 'YES'
);
SET @blog_shop_sql = IF(
    @blog_shop_nullable = 1,
    'SELECT 1',
    'ALTER TABLE tb_blog MODIFY COLUMN shop_id BIGINT NULL DEFAULT NULL COMMENT ''可选关联门店id'''
);
PREPARE blog_shop_statement FROM @blog_shop_sql;
EXECUTE blog_shop_statement;
DEALLOCATE PREPARE blog_shop_statement;
