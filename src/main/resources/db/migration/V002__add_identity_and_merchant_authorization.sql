-- Phase 2C identity, role and merchant ownership model.
-- Historical shops deliberately keep merchant_id NULL until an explicit claim or assignment.

SET @campushub_schema = DATABASE();

SET @user_status_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_user'
      AND COLUMN_NAME = 'status'
);
SET @user_status_sql = IF(
    @user_status_exists = 0,
    'ALTER TABLE tb_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE'' COMMENT ''账号状态'' AFTER icon',
    'SELECT 1'
);
PREPARE user_status_statement FROM @user_status_sql;
EXECUTE user_status_statement;
DEALLOCATE PREPARE user_status_statement;

CREATE TABLE IF NOT EXISTS tb_user_role (
    user_id BIGINT(20) UNSIGNED NOT NULL COMMENT '账号ID',
    role VARCHAR(32) NOT NULL COMMENT 'USER, MERCHANT 或 ADMIN',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (user_id, role),
    INDEX idx_user_role_role (role)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;

INSERT IGNORE INTO tb_user_role (user_id, role)
SELECT id, 'USER' FROM tb_user;

CREATE TABLE IF NOT EXISTS tb_merchant (
    id BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '商户主体ID',
    name VARCHAR(128) NOT NULL COMMENT '商户名称',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '商户状态',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS tb_merchant_member (
    merchant_id BIGINT(20) UNSIGNED NOT NULL COMMENT '商户主体ID',
    user_id BIGINT(20) UNSIGNED NOT NULL COMMENT '账号ID',
    member_role VARCHAR(32) NOT NULL DEFAULT 'OPERATOR' COMMENT 'OWNER 或 OPERATOR',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '成员关系状态',
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (merchant_id, user_id),
    INDEX idx_merchant_member_user_status (user_id, status)
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci;

SET @shop_merchant_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_shop'
      AND COLUMN_NAME = 'merchant_id'
);
SET @shop_merchant_sql = IF(
    @shop_merchant_exists = 0,
    'ALTER TABLE tb_shop ADD COLUMN merchant_id BIGINT(20) UNSIGNED NULL COMMENT ''所属商户；NULL 表示平台托管'' AFTER type_id',
    'SELECT 1'
);
PREPARE shop_merchant_statement FROM @shop_merchant_sql;
EXECUTE shop_merchant_statement;
DEALLOCATE PREPARE shop_merchant_statement;

SET @shop_merchant_index_exists = (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = @campushub_schema
      AND TABLE_NAME = 'tb_shop'
      AND INDEX_NAME = 'idx_shop_merchant'
);
SET @shop_merchant_index_sql = IF(
    @shop_merchant_index_exists = 0,
    'CREATE INDEX idx_shop_merchant ON tb_shop (merchant_id)',
    'SELECT 1'
);
PREPARE shop_merchant_index_statement FROM @shop_merchant_index_sql;
EXECUTE shop_merchant_index_statement;
DEALLOCATE PREPARE shop_merchant_index_statement;
