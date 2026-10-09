-- Phase 4B candidate indexes for shop type/price and score ordering.
-- These indexes do not make leading-wildcard keyword searches or Haversine distance calculations indexable,
-- and their query performance has not been measured. An existing index with the same name but a different
-- definition is treated as present and is not repaired automatically.

SET @campushub_schema = DATABASE();

SET @shop_search_type_price_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_shop'
      AND INDEX_NAME = 'idx_shop_search_type_price'
);
SET @shop_search_type_price_sql = IF(
    @shop_search_type_price_exists = 0,
    'CREATE INDEX idx_shop_search_type_price ON tb_shop (type_id, avg_price, id)',
    'SELECT 1'
);
PREPARE shop_search_type_price_statement FROM @shop_search_type_price_sql;
EXECUTE shop_search_type_price_statement;
DEALLOCATE PREPARE shop_search_type_price_statement;

SET @shop_search_score_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_shop'
      AND INDEX_NAME = 'idx_shop_search_score'
);
SET @shop_search_score_sql = IF(
    @shop_search_score_exists = 0,
    'CREATE INDEX idx_shop_search_score ON tb_shop (score, id)',
    'SELECT 1'
);
PREPARE shop_search_score_statement FROM @shop_search_score_sql;
EXECUTE shop_search_score_statement;
DEALLOCATE PREPARE shop_search_score_statement;
