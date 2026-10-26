package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessExportMigrationTest {
    @Test
    void v2AddsPersistentTaskDedicatedPermissionAndSingleActiveGuard() throws Exception {
        String sql;
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V2__business_export.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("CREATE TABLE business_export_task"));
        assertTrue(sql.contains("uk_business_export_active_guard"));
        assertTrue(sql.contains("'data.export'"));
        assertTrue(sql.contains("r.code IN ('ADMIN', 'OWNER')"));
        assertTrue(sql.contains("filters_json json NOT NULL"));
        assertFalse(sql.toLowerCase().contains("password_hash"));
        assertFalse(sql.toLowerCase().contains("system_user_session"));
    }
}
