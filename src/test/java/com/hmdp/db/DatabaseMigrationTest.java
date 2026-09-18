package com.hmdp.db;

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
}
