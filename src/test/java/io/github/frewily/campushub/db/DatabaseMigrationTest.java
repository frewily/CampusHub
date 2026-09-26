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
}
