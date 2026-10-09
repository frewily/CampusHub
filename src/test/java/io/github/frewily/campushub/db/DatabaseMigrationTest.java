package io.github.frewily.campushub.db;

import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseMigrationTest {

    @Test
    void shouldDefineFinalUniqueConstraintsForFollowAndVoucherOrder() throws IOException {
        String migration = StreamUtils.copyToString(
                getClass().getResourceAsStream("/db/migration/V001__add_business_unique_constraints.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains(
                "CREATE UNIQUE INDEX uk_follow_user_target ON tb_follow (user_id, follow_user_id)"));
        assertTrue(normalized.contains(
                "CREATE UNIQUE INDEX uk_voucher_order_user_voucher ON tb_voucher_order (user_id, voucher_id)"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(normalized.contains("PREPARE follow_index_statement"));
        assertTrue(normalized.contains("PREPARE voucher_order_index_statement"));
    }

    @Test
    void shouldDefineIdempotentIdentityAndMerchantAuthorizationSchema() throws IOException {
        String migration = StreamUtils.copyToString(
                getClass().getResourceAsStream("/db/migration/V002__add_identity_and_merchant_authorization.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains("ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT ''ACTIVE''"));
        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS tb_user_role"));
        assertTrue(normalized.contains("INSERT IGNORE INTO tb_user_role (user_id, role) SELECT id, 'USER' FROM tb_user"));
        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS tb_merchant"));
        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS tb_merchant_member"));
        assertTrue(normalized.contains("ADD COLUMN merchant_id BIGINT(20) UNSIGNED NULL"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.COLUMNS"));
        assertTrue(normalized.contains("CREATE INDEX idx_shop_merchant ON tb_shop (merchant_id)"));
    }

    @Test
    void shouldDefineIdempotentOrderCancellationOutboxContract() throws IOException {
        String migration = StreamUtils.copyToString(
                getClass().getResourceAsStream("/db/migration/V003__add_order_cancellation_outbox.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS tb_order_cancellation"));
        assertTrue(normalized.contains("order_id BIGINT NOT NULL"));
        assertTrue(normalized.contains("PRIMARY KEY (order_id)"));
        assertTrue(normalized.contains("status VARCHAR(24) NOT NULL DEFAULT 'PENDING'"));
        assertTrue(normalized.contains("PENDING, COMPLETED, REQUIRES_REVIEW"));
        assertTrue(normalized.contains("lease_token VARCHAR(36) NULL"));
        assertTrue(normalized.contains("lease_until DATETIME NULL"));
        assertTrue(normalized.contains("expires_at_ms BIGINT NOT NULL"));
        assertTrue(normalized.contains("Redis活动结束后24小时的原截止毫秒时间戳，不是本次取消时间"));
        assertTrue(normalized.contains(
                "INDEX idx_order_cancellation_due (status, next_attempt_at, lease_until)"));
        assertTrue(normalized.contains("ENGINE = InnoDB CHARACTER SET = utf8mb4"));
        assertTrue(normalized.contains("must be written in one transaction"));
    }

    @Test
    void shouldDefineIdempotentShopCacheInvalidationOutboxContract() throws IOException {
        String migration = StreamUtils.copyToString(
                getClass().getResourceAsStream("/db/migration/V004__add_shop_cache_invalidation_outbox.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CREATE TABLE IF NOT EXISTS tb_shop_cache_invalidation"));
        assertTrue(normalized.contains("shop_id BIGINT UNSIGNED NOT NULL"));
        assertTrue(normalized.contains("generation VARCHAR(36) NOT NULL"));
        assertTrue(normalized.contains("attempts INT UNSIGNED NOT NULL DEFAULT 0"));
        assertTrue(normalized.contains("last_error VARCHAR(64) NULL"));
        assertTrue(normalized.contains("create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"));
        assertTrue(normalized.contains(
                "update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"));
        assertTrue(normalized.contains("PRIMARY KEY (shop_id)"));
        assertTrue(normalized.contains("INDEX idx_shop_cache_invalidation_pending (update_time, shop_id)"));
        assertTrue(normalized.contains("ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci"));
        assertTrue(normalized.contains("generation is a per-write database UUID"));
        assertTrue(normalized.contains("not a Redis epoch"));
        assertTrue(normalized.contains("fresh random Redis epoch for every invalidation to avoid ABA"));
        assertTrue(normalized.contains("safe exception class name"));
    }

    @Test
    void shouldDefineIdempotentCandidateShopSearchIndexes() throws IOException {
        String migration = StreamUtils.copyToString(
                getClass().getResourceAsStream("/db/migration/V005__add_shop_search_indexes.sql"),
                StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CREATE INDEX idx_shop_search_type_price ON tb_shop (type_id, avg_price, id)"));
        assertTrue(normalized.contains("CREATE INDEX idx_shop_search_score ON tb_shop (score, id)"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(normalized.contains("TABLE_SCHEMA = @campushub_schema"));
        assertTrue(normalized.contains("TABLE_NAME = 'tb_shop'"));
        assertTrue(normalized.contains("INDEX_NAME = 'idx_shop_search_type_price'"));
        assertTrue(normalized.contains("INDEX_NAME = 'idx_shop_search_score'"));
        assertTrue(normalized.contains("PREPARE shop_search_type_price_statement"));
        assertTrue(normalized.contains("EXECUTE shop_search_type_price_statement"));
        assertTrue(normalized.contains("DEALLOCATE PREPARE shop_search_type_price_statement"));
        assertTrue(normalized.contains("PREPARE shop_search_score_statement"));
        assertTrue(normalized.contains("EXECUTE shop_search_score_statement"));
        assertTrue(normalized.contains("DEALLOCATE PREPARE shop_search_score_statement"));
        assertTrue(normalized.contains("leading-wildcard keyword searches or Haversine distance calculations"));
        assertTrue(normalized.contains("performance has not been measured"));
        assertTrue(normalized.contains("is not repaired automatically"));
    }
}
