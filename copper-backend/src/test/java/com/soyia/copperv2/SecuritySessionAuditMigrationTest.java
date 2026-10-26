package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecuritySessionAuditMigrationTest {
    @Test
    void v5RetainsRevokedSessionsAndAddsTraceableAuditEnvelope() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V5__security_session_audit.sql")) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN revoked_at"));
            assertTrue(sql.contains("ADD COLUMN revoke_reason"));
            assertTrue(sql.contains("ADD COLUMN event_id"));
            assertTrue(sql.contains("ADD COLUMN result_code"));
            assertTrue(sql.contains("ADD COLUMN trace_id"));
            assertTrue(sql.contains("ADD UNIQUE KEY uk_security_audit_event"));
            assertFalse(sql.toLowerCase().contains("password_hash"));
            assertFalse(sql.toLowerCase().contains("token_hash"));
            assertFalse(sql.matches("(?is).*DROP\\s+TABLE.*"));
        }
    }
}
