package com.soyia.copperv2;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopperBarPresetDimensionsMigrationTest {
    @Test
    void v6BackfillsStructuredWidthAndThicknessWithoutOverwritingCustomerValues() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/db/migration/V6__copper_bar_preset_dimensions.sql")) {
            assertNotNull(input);
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            for (Map.Entry<String, String> preset : Map.of(
                    "HTCJ25-3", "'25' AS width_text, 25.0000000000 AS width_value",
                    "HTCJ25-6", "'25', 25.0000000000, '6', 6.0000000000",
                    "HTCJ30-6", "'30', 30.0000000000, '6', 6.0000000000",
                    "HTCJ40-6", "'40', 40.0000000000, '6', 6.0000000000",
                    "HTCJ50-6", "'50', 50.0000000000, '6', 6.0000000000",
                    "HTCJ60-6", "'60', 60.0000000000, '6', 6.0000000000",
                    "HTCJ80-8", "'80', 80.0000000000, '8', 8.0000000000",
                    "HTCJ100-6", "'100', 100.0000000000, '6', 6.0000000000"
            ).entrySet()) {
                assertTrue(sql.contains("'" + preset.getKey() + "'"), "missing preset " + preset.getKey());
                assertTrue(sql.contains(preset.getValue()), "wrong dimensions for " + preset.getKey());
            }

            assertTrue(sql.contains("sf.field_name IN ('宽度', '厚度')"));
            assertTrue(sql.contains("WHERE pv.value_text = '' AND pv.value_decimal IS NULL"));
            assertTrue(sql.contains("LEFT JOIN product_spec_preset_value existing"));
            assertTrue(sql.contains("AND existing.id IS NULL"));
            assertTrue(sql.contains("'$.宽度', width_value.value_text"));
            assertTrue(sql.contains("'$.厚度', thickness_value.value_text"));
            assertTrue(sql.contains("'$.宽度'"));
            assertTrue(sql.contains("'$.厚度'"));
            assertFalse(sql.contains("'长度'"), "25x3 must not be interpreted as length");
            assertFalse(sql.matches("(?is).*\\b(DELETE FROM|DROP TABLE|TRUNCATE TABLE)\\b.*"));
        }
    }
}
