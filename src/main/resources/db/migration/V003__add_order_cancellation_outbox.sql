-- Phase 3C cancellation recovery outbox.
-- Cancellation status, database stock refill, and this outbox record must be written in one transaction.
-- This table does not add or remove original order statuses and does not change the one-order-per-user constraint.
-- Never store access tokens, passwords, or other credentials in this table.

CREATE TABLE IF NOT EXISTS tb_order_cancellation (
    order_id BIGINT NOT NULL COMMENT '用户订单ID；与原订单对应，不自增',
    user_id BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    voucher_id BIGINT UNSIGNED NOT NULL COMMENT '优惠券ID',
    expires_at_ms BIGINT NOT NULL COMMENT 'Redis活动结束后24小时的原截止毫秒时间戳，不是本次取消时间',
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, COMPLETED, REQUIRES_REVIEW',
    attempts INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
    next_attempt_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次处理时间',
    lease_token VARCHAR(36) NULL COMMENT '处理租约令牌',
    lease_until DATETIME NULL COMMENT '处理租约到期时间',
    last_error VARCHAR(64) NULL COMMENT '原因码或异常类名，不存异常文本',
    redis_result VARCHAR(32) NULL COMMENT 'RELEASED, ALREADY_RELEASED, EXPIRED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    completed_at DATETIME NULL COMMENT '完成时间',
    PRIMARY KEY (order_id),
    INDEX idx_order_cancellation_due (status, next_attempt_at, lease_until)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT = '订单取消恢复 outbox；记录取消处理状态与 Redis 回补结果';
