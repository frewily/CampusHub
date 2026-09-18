-- Phase 1B business invariants.
-- Existing duplicate rows must be resolved before this migration can create the indexes.
-- The INFORMATION_SCHEMA guards make the script safe to run again after a successful application.

SET @campushub_schema = DATABASE();

SET @follow_index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_follow'
      AND INDEX_NAME = 'uk_follow_user_target'
);
SET @follow_index_sql = IF(
    @follow_index_exists = 0,
    'CREATE UNIQUE INDEX uk_follow_user_target ON tb_follow (user_id, follow_user_id)',
    'SELECT 1'
);
PREPARE follow_index_statement FROM @follow_index_sql;
EXECUTE follow_index_statement;
DEALLOCATE PREPARE follow_index_statement;

SET @voucher_order_index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_voucher_order'
      AND INDEX_NAME = 'uk_voucher_order_user_voucher'
);
SET @voucher_order_index_sql = IF(
    @voucher_order_index_exists = 0,
    'CREATE UNIQUE INDEX uk_voucher_order_user_voucher ON tb_voucher_order (user_id, voucher_id)',
    'SELECT 1'
);
PREPARE voucher_order_index_statement FROM @voucher_order_index_sql;
EXECUTE voucher_order_index_statement;
DEALLOCATE PREPARE voucher_order_index_statement;
