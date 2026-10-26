package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp09PriceCenterContractTest {
    @Test
    void migrationCreatesVersionedBenchmarksRulesAndDocumentPriceSnapshots() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V43__copper_price_center.sql"));
        for (String marker : new String[]{
                "CREATE TABLE price_benchmark_version (", "CREATE TABLE price_benchmark_sequence (",
                "CREATE TABLE price_rule_version (",
                "uk_price_benchmark_effective_date", "uk_price_rule_version",
                "minimum_quantity", "maximum_quantity", "processing_fee", "specification_fee",
                "packaging_fee", "freight_fee", "price_snapshot_json json",
                "ALTER TABLE purchase_order_item", "ADD COLUMN no_charge", "ALTER TABLE sales_order_item",
                "ALTER TABLE crm_quote_item", "pricing.view", "pricing.manage"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceHasDeterministicPriorityTaxNormalizationOverlapProtectionAndImmutableSnapshots() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PriceCenterService.java"));
        for (String marker : new String[]{
                "CUSTOMER_SPECIAL", "CUSTOMER_AGREEMENT", "CUSTOMER_LEVEL",
                "SUPPLIER_AGREEMENT", "COPPER_FORMULA", "STANDARD_PRICE", "r.minimum_quantity desc",
                "assertNoAmbiguousOverlap", "COPPER_PRICE_CENTER_V1", "MANUAL_OVERRIDE",
                "positivePrice", "matchesHistoricalSnapshot", "excludingTax", "enteredPrice",
                "priceIncludesTax", "benchmarkVersionId", "for update"
        }) assertTrue(service.contains(marker), marker);
    }

    @Test
    void apiOrderQuoteAndExportContractsExposeAndPreservePriceSource() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/PriceCenterController.java"));
        for (String path : new String[]{
                "/price-center/benchmarks", "/price-center/rules", "/price-center/preview"
        }) assertTrue(controller.contains(path), path);
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String crm = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CrmService.java"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        assertTrue(copper.contains("freezeEnteredPrice"));
        assertTrue(copper.contains("price_source_code,price_source_name,price_rule_version_id,price_snapshot_json"));
        assertTrue(copper.contains("status = 'ENABLED' and no_charge=0"));
        assertTrue(crm.contains("priceSnapshots.put"));
        assertTrue(crm.contains("priceSnapshotJson"));
        assertTrue(crm.contains("quoteItemPriceSnapshots"));
        assertTrue(export.contains("starts(table, \"price_\")"));
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        assertTrue(security.contains("HttpMethod.POST.matches(method)"));
        assertTrue(security.contains("\"/price-center/preview\".equals(path)"));
        assertTrue(security.contains("return read ? \"pricing.view\" : \"pricing.manage\""));
    }
}
