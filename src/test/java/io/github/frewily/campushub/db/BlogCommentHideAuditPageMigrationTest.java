package io.github.frewily.campushub.db;

import org.junit.jupiter.api.Test;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BlogCommentHideAuditPageMigrationTest {

    @Test
    void migrationDefinesIdempotentCandidateIndexWithoutRepairingOrChangingData() throws IOException {
        String migration = StreamUtils.copyToString(getClass().getResourceAsStream(
                "/db/migration/V009__add_blog_comment_hide_audit_page_index.sql"), StandardCharsets.UTF_8);
        String normalized = migration.replace("`", "").replaceAll("\\s+", " ");

        assertTrue(normalized.contains("CREATE INDEX idx_blog_comment_hide_audit_page ON tb_blog_comment_hide_audit (blog_id, id)"));
        assertTrue(normalized.contains("INFORMATION_SCHEMA.STATISTICS"));
        assertTrue(normalized.contains("TABLE_SCHEMA = @campushub_schema"));
        assertTrue(normalized.contains("TABLE_NAME = 'tb_blog_comment_hide_audit'"));
        assertTrue(normalized.contains("INDEX_NAME = 'idx_blog_comment_hide_audit_page'"));
        assertTrue(normalized.contains("PREPARE blog_comment_hide_audit_page_statement"));
        assertTrue(normalized.contains("EXECUTE blog_comment_hide_audit_page_statement"));
        assertTrue(normalized.contains("DEALLOCATE PREPARE blog_comment_hide_audit_page_statement"));
        assertTrue(!normalized.matches("(?is).*\\b(INSERT|UPDATE|DELETE|REPLACE)\\b.*"),
                "index migration must not backfill, repair, or mutate rows");
    }
}
