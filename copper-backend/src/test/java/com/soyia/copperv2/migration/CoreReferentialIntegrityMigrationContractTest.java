package com.soyia.copperv2.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreReferentialIntegrityMigrationContractTest {
    private static final int CORE_RELATION_COUNT = 54;

    @Test
    void migrationPreflightsEveryRelationAndNeverCascadesHistory() throws IOException {
        String resource = "/db/migration/V67__core_document_referential_integrity.sql";
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);

            assertEquals(CORE_RELATION_COUNT, occurrences(sql, "CALL v67_assert_no_orphans("));
            assertEquals(CORE_RELATION_COUNT, occurrences(sql, "FOREIGN KEY ("));
            assertEquals(CORE_RELATION_COUNT, occurrences(sql, "ON DELETE RESTRICT ON UPDATE RESTRICT"));
            assertFalse(sql.contains("CASCADE"));
            assertTrue(sql.indexOf("DROP PROCEDURE v67_assert_no_orphans;")
                    < sql.indexOf("ALTER TABLE purchase_order_item"));
        }
    }

    private int occurrences(String source, String token) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(token, offset)) >= 0) {
            count++;
            offset += token.length();
        }
        return count;
    }
}
