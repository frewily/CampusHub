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
}
