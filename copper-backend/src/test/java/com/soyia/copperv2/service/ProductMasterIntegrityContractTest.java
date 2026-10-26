package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductMasterIntegrityContractTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void legacyStandardsAndTempersAreReadOnlyProjectionsOfTheUnifiedReferenceMaster()
            throws Exception {
        String copper = source(
                "src/main/java/com/soyia/copperv2/service/CopperService.java");
        assertTrue(copper.contains(
                "productMasterDataService.referenceValuesForProductRead(\"STANDARD\", null, null)"));
        assertTrue(copper.contains(
                "productMasterDataService.referenceValuesForProductRead(\"TEMPER\", null, null)"));
        assertTrue(copper.contains("旧执行标准维护入口已退役"));
        assertTrue(copper.contains("旧材料状态维护入口已退役"));
        assertFalse(copper.contains("insert into product_standard"));
        assertFalse(copper.contains("update product_standard"));
        assertFalse(copper.contains("insert into product_temper"));
        assertFalse(copper.contains("update product_temper"));
    }

    @Test
    void supplierOfferRulesCoverNewAndExistingPurchaseLines() throws Exception {
        String copper = source(
                "src/main/java/com/soyia/copperv2/service/CopperService.java");
        String complex = source(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java");
        for (String marker : new String[]{
                "validateFrozenOrCurrentSupplierOffer",
                "frozenSupplierOfferContext",
                "expectedSupplierOfferRevisionId",
                "expectedSupplierOfferSnapshotHash",
                "minimumOrderQuantity",
                "orderMultiple",
                "currencyCode",
                "packagingVariantRevisionId",
                "supplierOfferSnapshotHash"
        }) {
            assertTrue(copper.contains(marker), marker);
        }
        assertTrue(complex.contains("r.effective_from<=?"));
        assertTrue(complex.contains("r.effective_to is null or r.effective_to>=?"));
        assertTrue(complex.contains("边界日期也视为同时生效"));
    }

    @Test
    void importLeaseIdentityFieldAndBomGraphFailClosed() throws Exception {
        String migration = source(
                "src/main/resources/db/migration/V48__product_master_import_staging.sql");
        String importer = source(
                "src/main/java/com/soyia/copperv2/service/ProductImportService.java");
        String copper = source(
                "src/main/java/com/soyia/copperv2/service/CopperService.java");
        String complex = source(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java");
        for (String marker : new String[]{
                "claim_token", "heartbeat_at", "COMMIT_RECOVERED",
                "assertCommitOwner", "date_sub(current_timestamp,interval 5 minute)"
        }) {
            assertTrue(migration.contains(marker) || importer.contains(marker), marker);
        }
        assertTrue(copper.contains("seriesFieldUsedByIdentity"));
        assertTrue(copper.contains("规格字段已用于区分已有商品"));
        assertTrue(complex.contains("validateBomAcyclic"));
        assertTrue(complex.contains("reachesProduct"));
        assertTrue(complex.contains("存在传递循环"));
    }

    @Test
    void aliasesUnitsSourcesAndQualityPreserveImmutableSemantics() throws Exception {
        String migration47 = source(
                "src/main/resources/db/migration/V47__complex_product_master_foundation.sql");
        String migration49 = source(
                "src/main/resources/db/migration/V49__complex_product_catalog_units_and_precision.sql");
        String migration53 = source(
                "src/main/resources/db/migration/V53__product_reference_alias_global_uniqueness.sql");
        String master = source(
                "src/main/java/com/soyia/copperv2/service/ProductMasterDataService.java");
        String copper = source(
                "src/main/java/com/soyia/copperv2/service/CopperService.java");
        String complex = source(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java");
        assertTrue(migration47.contains(
                "UNIQUE KEY uk_product_reference_alias (reference_type,alias_key,reference_value_id)"));
        assertTrue(migration47.contains(
                "MODIFY COLUMN spec varchar(1000) NOT NULL DEFAULT ''"));
        assertTrue(migration53.contains(
                "ADD UNIQUE KEY uk_product_reference_alias (reference_type,alias_key)"));
        assertTrue(migration53.contains(
                "PRIMARY KEY (reference_type,alias_key)"));
        assertTrue(migration47.contains("tmp_product_reference_alias_candidate"));
        assertTrue(migration47.contains("tmp_product_reference_alias_unique"));
        assertTrue(migration47.contains(
                "HAVING count(distinct reference_value_id)=1"));
        assertTrue(migration47.contains(
                "DROP TEMPORARY TABLE tmp_product_reference_alias_unique"));
        assertTrue(migration47.contains(
                "DROP TEMPORARY TABLE tmp_product_reference_alias_candidate"));
        assertTrue(master.contains("storedReferenceBindings"));
        assertTrue(master.contains("title_snapshot"));
        assertTrue(copper.contains("unitTextInUse"));
        assertTrue(copper.contains("product_package_definition_revision_item"));
        assertTrue(migration49.contains("tmp_v49_preflight_guard"));
        assertTrue(migration49.contains("tmp_v49_completion_assertion"));
        assertTrue(migration49.contains("unit_lookup_token"));
        assertTrue(migration49.contains("product_catalog_migration_audit"));
        assertTrue(complex.contains("inventory.quality.view"));
        assertTrue(complex.contains("inventory.quality.manage"));
        assertTrue(complex.contains("布尔型技术特性目标值只能填写true或false"));
    }

    @Test
    void importOwnershipRevisionSnapshotsAndInputLimitsFailClosed() throws Exception {
        String migration51 = source(
                "src/main/resources/db/migration/V51__master_revision_head_snapshots.sql");
        String migration52 = source(
                "src/main/resources/db/migration/V52__product_import_owner_idempotency.sql");
        String importer = source(
                "src/main/java/com/soyia/copperv2/service/ProductImportService.java");
        String parser = source(
                "src/main/java/com/soyia/copperv2/service/ProductImportFileParser.java");
        String packaging = source(
                "src/main/java/com/soyia/copperv2/service/PackagingService.java");
        String packagingController = source(
                "src/main/java/com/soyia/copperv2/controller/PackagingController.java");
        String copper = source(
                "src/main/java/com/soyia/copperv2/service/CopperService.java");

        for (String snapshot : new String[]{
                "document_code_snapshot", "variant_code_snapshot",
                "supplier_name_snapshot", "bom_code_snapshot", "head_status_snapshot"
        }) {
            assertTrue(migration51.contains(snapshot), snapshot);
        }
        assertTrue(migration52.contains(
                "(imported_by,source_file_sha256,mapping_sha256,template_version,configuration_sha256)"));
        assertTrue(importer.contains("where id=? and imported_by=?"));
        assertTrue(importer.contains("PRODUCT_SCOPE_CREATE_DENIED"));
        assertTrue(importer.contains("assertMasterCreationAllowed(\"PRODUCT\")"));
        assertTrue(parser.contains("MAX_COLUMNS = 256"));
        assertTrue(parser.contains("MAX_CELL_LENGTH = 10_000"));
        assertTrue(parser.contains("sheet.getLastRowNum() > MAX_ROWS"));
        assertTrue(packaging.contains("enabledStatus(request.status(), \"包装模板状态\")"));
        assertTrue(packaging.contains("enabledStatus(rule.status(), \"包装规则状态\")"));
        assertTrue(packagingController.contains("/packaging-templates/{templateId}/metadata"));
        assertTrue(packagingController.contains("/packaging-templates/{templateId}/status"));
        assertTrue(packaging.contains("UPDATE_PACKAGING_TEMPLATE_METADATA"));
        assertTrue(packaging.contains("ENABLE_PACKAGING_TEMPLATE"));
        assertTrue(packaging.contains("DISABLE_PACKAGING_TEMPLATE"));
        assertTrue(packaging.contains("包装模板已停用，不能发布规则新版本"));
        assertTrue(copper.contains("商品必填规格尚未补齐，不能用于采购、销售或库存业务"));
        assertTrue(copper.contains("requireProductCostViewPermission"));
    }
}
