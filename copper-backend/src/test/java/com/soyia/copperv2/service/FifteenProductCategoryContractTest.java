package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FifteenProductCategoryContractTest {
    @Test
    void migrationEnablesBaselineAndRepairsSkuDimensions() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V31__enable_complete_product_category_baseline.sql"));
        assertTrue(migration.contains("WHERE id = 106"), "特种铜管分类必须启用");
        assertTrue(migration.contains("WHERE id = 2004"), "紫铜直管系列必须启用");
        assertTrue(migration.contains("WHERE id = 300082"), "管件国标规格必须成为 SKU 维度");
        assertTrue(migration.contains("WHERE id = 300123"), "异形管规格描述必须成为 SKU 维度");
        for (String section : new String[]{"矩形管", "方形管", "椭圆管", "半圆管", "D型管", "十字花管"}) {
            assertTrue(migration.contains("\"" + section + "\""), section + " 必须是独立截面选项");
        }
    }

    @Test
    void newDocumentsRejectDisabledProductHierarchy() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(source.contains("p.status product_status"));
        assertTrue(source.contains("s.status series_status"));
        assertTrue(source.contains("c.status category_status"));
        assertTrue(source.contains("商品已停用，不能用于新增业务单据"));
        assertTrue(source.contains("已停用，不能用于新商品"));
    }

    @Test
    void realMysqlAcceptanceCoversAllFamiliesAndPackagingSemantics() throws Exception {
        String acceptance = Files.readString(Path.of(
                "src/test/java/com/soyia/copperv2/service/FifteenProductCategoryMySqlAcceptanceTest.java"));
        String runner = Files.readString(Path.of("tests/run-15-product-category-mysql.sh"));
        for (int seriesId = 2001; seriesId <= 2015; seriesId++) {
            assertTrue(acceptance.contains("family(" + seriesId + ","), "缺少系列 " + seriesId + " 验收场景");
        }
        for (String operation : new String[]{
                "createProduct", "createPurchaseOrder", "confirmPurchaseOrder", "createInbound",
                "submitInbound", "auditInbound", "createSalesOrder", "confirmSalesOrder",
                "createOutbound", "createSalesReturn", "createPurchaseReturn", "confirmPurchaseReturn",
                "inventoryBatches"
        }) {
            assertTrue(acceptance.contains(operation + "("), operation);
        }
        assertTrue(acceptance.contains("ACTUAL_WEIGHT"));
        assertTrue(acceptance.contains("FIXED"));
        assertTrue(acceptance.contains("20.345"));
        assertTrue(acceptance.contains("breakable()"));
        assertTrue(runner.contains("COPPER_15_CATEGORY_ACCEPTANCE=true"));
        assertTrue(runner.contains("FIFTEEN_PRODUCT_CATEGORY_ACCEPTANCE=PASS"));
    }
}
