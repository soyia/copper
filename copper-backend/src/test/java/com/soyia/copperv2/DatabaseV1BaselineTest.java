package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseV1BaselineTest {
    private static final String RESOURCE = "/db/migration/V1__baseline.sql";
    private static final String EXPECTED_SHA256 = "0f32a11c8d0393602b382581501dbbbc3823ef8287945aecb3103872967fcf7c";

    @Test
    void baselineIsPrefixFreeCompleteAndContainsNoBusinessSeeds() throws Exception {
        byte[] bytes;
        try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
            assertNotNull(input, "V1 baseline resource is missing");
            bytes = input.readAllBytes();
        }
        String sql = new String(bytes, StandardCharsets.UTF_8);

        assertEquals(EXPECTED_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertEquals(87, occurrences(sql, "CREATE TABLE `"), "V1 must contain all 87 application tables");
        assertFalse(sql.contains("CREATE TABLE `c_"), "V1 must not recreate c_-prefixed tables");
        assertFalse(sql.contains("CREATE DATABASE"));
        assertFalse(sql.contains("USE `"));

        for (String table : List.of(
                "partner", "product", "purchase_order", "inbound_order", "inventory_batch",
                "sales_order", "outbound_order", "receivable_movement", "payment", "payable_movement",
                "supplier_payment", "system_user", "system_user_session", "inbound_attachment")) {
            assertFalse(sql.contains("INSERT INTO `" + table + "`"), "business data must not be seeded: " + table);
        }

        for (String table : List.of(
                "organization_department", "unit", "product_category", "product_series",
                "product_spec_preset", "packaging_template", "security_permission", "security_role")) {
            assertTrue(sql.contains("INSERT INTO `" + table + "`"), "required system seed is missing: " + table);
        }
    }

    private int occurrences(String value, String marker) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(marker, index)) >= 0) {
            count++;
            index += marker.length();
        }
        return count;
    }
}
