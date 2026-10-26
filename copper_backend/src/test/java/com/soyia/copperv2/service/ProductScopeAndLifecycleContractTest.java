package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductScopeAndLifecycleContractTest {

    @Test
    void productListsAndPackagingReadsApplyRecordLevelScope() throws Exception {
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String packaging = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PackagingService.java"));

        assertTrue(copper.contains(
                "dataScopeService.filterMasterList(\"PRODUCT\", products, \"id\")"));
        assertTrue(packaging.contains(
                "dataScopeService.assertMasterAllowed(\"PRODUCT\", productId)"));
        assertTrue(packaging.contains(
                "dataScopeService.filterMasterIds("));
        assertTrue(packaging.contains(
                "dataScopeService.assertMasterAllowed(\"WAREHOUSE\", batch.warehouseId())"));
        assertTrue(packaging.contains(
                "dataScopeService.filterMasterList(\"PRODUCT\", rows, \"productId\")"));
    }

    @Test
    void historicalLinesUseFrozenSnapshotsWhileNewLinesRequireCurrentProduct() throws Exception {
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));

        assertTrue(copper.contains("requireHistoricalProduct("));
        assertTrue(copper.contains("requireProductAccessible("));
        assertTrue(copper.contains("orderItemProductIds("));
        assertTrue(copper.contains(
                "historicalProductIds.get(itemId)"));
        assertTrue(copper.contains(
                "? packagingService.planFromSnapshot(requiredSnapshot(historicalSnapshots, item.itemId()), planRequest)"));
        assertTrue(copper.contains(
                "productIds.forEach(this::requireProductAccessible)"));
        assertTrue(copper.contains(
                "if (purchaseOrderItemId > 0) {\n                requireProductAccessible(item.productId());"));
        assertTrue(copper.contains(
                "} else {\n                requireProduct(item.productId());"));
    }
}
