-- Phase 4A shop cache invalidation outbox.
-- generation is a per-write database UUID used only for conditional row deletion; it is not a Redis epoch.
-- Workers must generate a fresh random Redis epoch for every invalidation to avoid ABA.
-- Repeated Redis cache invalidation has no inventory side effects, so this table needs no state or lease.
-- last_error stores only a safe exception class name, never exception text or credentials.

CREATE TABLE IF NOT EXISTS tb_shop_cache_invalidation (
    shop_id BIGINT UNSIGNED NOT NULL COMMENT '店铺ID',
    generation VARCHAR(36) NOT NULL COMMENT '每次数据库写入生成的新UUID，仅用于数据库条件删除，不是Redis epoch',
    attempts INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处理失败次数，最多记录1000000次',
    last_error VARCHAR(64) NULL COMMENT '安全的异常类名，不存异常文本',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (shop_id),
    INDEX idx_shop_cache_invalidation_pending (update_time, shop_id)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '店铺缓存失效 outbox';
