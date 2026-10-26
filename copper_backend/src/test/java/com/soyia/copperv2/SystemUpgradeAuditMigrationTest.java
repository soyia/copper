package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemUpgradeAuditMigrationTest {

    @Test
    void v4AddsAppendOnlyUpgradeAuditWithoutTouchingBusinessBalances() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V4__system_upgrade_audit.sql")) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("CREATE TABLE system_upgrade_audit"));
            assertTrue(sql.contains("UNIQUE KEY uk_system_upgrade_audit_upgrade (upgrade_id)"));
            assertTrue(sql.contains("release_manifest_sha256 char(64) NOT NULL"));
            assertTrue(sql.contains("status IN ('MIGRATING', 'MIGRATION_VALIDATED', 'SWITCHED', 'FAILED', 'ROLLED_BACK')"));
            assertFalse(sql.matches("(?is).*\\b(ALTER TABLE|DELETE FROM|DROP TABLE|TRUNCATE TABLE)\\b.*"));
            assertFalse(sql.matches("(?is).*\\bUPDATE\\s+[a-z0-9_`]+\\s+SET\\b.*"));
        }
    }
}
