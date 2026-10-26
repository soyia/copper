package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalQuantityPrecisionMigrationContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V50__complete_operational_quantity_precision.sql");

    @Test
    void v50CompletesEveryPreviouslyNarrowOperationalAndPackagingQuantity() throws Exception {
        String sql = normalized(Files.readString(MIGRATION));
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("inbound_item", List.of("requested_quantity", "pricing_quantity"));
        expected.put("inbound_review_snapshot_item",
                List.of("requested_quantity", "pricing_quantity"));
        expected.put("outbound_item", List.of("requested_quantity", "pricing_quantity"));
        expected.put("purchase_order_item", List.of("requested_quantity", "pricing_quantity"));
        expected.put("sales_order_item", List.of("requested_quantity", "pricing_quantity"));
        expected.put("inbound_package_instance", List.of("actual_base_quantity"));
        expected.put("inventory_package_balance", List.of("quantity"));
        expected.put("inventory_package_instance", List.of("actual_base_quantity"));
        expected.put("inventory_package_movement", List.of("from_quantity", "to_quantity"));
        expected.put("landed_cost_allocation_item", List.of("basis_value"));
        expected.put("product_package_rule",
                List.of("fixed_child_quantity", "reference_base_quantity"));
        expected.put("product_series_package_unit", List.of("exchange_rate", "volume"));
        expected.put("product_series_price_unit", List.of("exchange_rate"));

        expected.forEach((table, columns) -> {
            assertTrue(sql.contains("alter table " + table), table + " 必须由 V50 迁移");
            columns.forEach(column -> assertTrue(
                    sql.contains("modify " + column + " decimal(24,6)"),
                    table + "." + column + " 必须使用 decimal(24,6)"));
        });
    }

    @Test
    void v50DoesNotChangeAccountingOrTaxPrecision() throws Exception {
        String sql = normalized(Files.readString(MIGRATION));

        assertFalse(sql.contains("unit_price"));
        assertFalse(sql.contains("unit_cost"));
        assertFalse(sql.contains("tax_rate"));
        assertFalse(sql.contains("discount_rate"));
        assertFalse(sql.matches(
                "(?s).*modify\\s+[^,;]*(unit_price|unit_cost|amount|tax_rate|discount_rate)\\s+decimal.*"),
                "V50 不得修改价格、金额、税率或折扣率字段的精度");
        assertFalse(sql.contains("float"));
        assertFalse(sql.contains("double"));
    }

    private String normalized(String value) {
        return value.toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
