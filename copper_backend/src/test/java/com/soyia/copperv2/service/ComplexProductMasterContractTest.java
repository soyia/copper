package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexProductMasterContractTest {
    @Test
    void migrationKeepsComplexMasterDataVersionedAndVendorNeutral() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V47__complex_product_master_foundation.sql"));
        for (String table : new String[]{
                "product_identity_schema_revision", "product_identity_rule",
                "product_reference_value", "product_reference_alias", "product_reference_binding",
                "product_source_document_revision", "product_source_link",
                "product_series_allowed_unit", "packaging_variant_revision",
                "supplier_product_offer_revision", "supplier_product_packaging_binding",
                "product_port", "conductor_construction", "conductor_construction_component",
                "product_technical_spec_revision", "product_technical_characteristic",
                "inventory_batch_quality_certificate", "inventory_batch_quality_result",
                "product_bom_revision", "product_bom_item"
        }) {
            assertTrue(migration.contains(" " + table + " "), table);
        }
        assertTrue(migration.contains("identity_schema_version_snapshot"));
        assertTrue(migration.contains("identity_status"));
        assertTrue(migration.contains("C18150"));
        assertTrue(migration.contains("QSn6.5-0.1"));
        assertTrue(migration.contains("'N35'"));
        assertTrue(migration.contains("'N42SH'"));
        assertTrue(migration.contains("'1/2H'"));
        assertFalse(migration.contains("product_assembly_order"),
                "没有领料、成本、过账和冲正闭环前不得暴露装配交易表");
    }

    @Test
    void smallTraderApiExposesOnlyOperationalProductMasterBoundaries() throws Exception {
        String productMaster = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductMasterDataService.java"));
        String complex = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java"));
        String complexController = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/ComplexProductMasterController.java"));
        String productMasterController = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/ProductMasterDataController.java"));
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String packaging = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PackagingService.java"));

        for (String method : new String[]{
                "syncIdentitySchemaFromSeriesFields", "duplicateCheck",
                "evaluateCandidateIdentity", "resolveReference", "finalizeProductIdentity"
        }) assertTrue(productMaster.contains(method + "("), method);
        for (String method : new String[]{
                "createPackagingVariant", "updatePackagingVariant",
                "createSupplierOffer", "updateSupplierOffer", "savePorts",
                "saveConductorConstruction", "publishTechnicalSpec",
                "saveQualityCertificate", "publishBom"
        }) assertTrue(complex.contains(method + "("), method);
        for (String path : new String[]{"/packaging-variants", "/supplier-offers"}) {
            assertTrue(complexController.contains(path), path);
        }
        for (String path : new String[]{
                "/ports", "/conductor-construction", "/technical-specs",
                "/quality-certificates", "/bom/revisions"
        }) {
            assertFalse(complexController.contains(path),
                    path + " 不应再作为小微经销商商品建档入口");
        }
        for (String path : new String[]{
                "/product-source-documents", "/product-source-links",
                "/identity-schema", "/identity/publish"
        }) {
            assertFalse(productMasterController.contains(path),
                    path + " 不应再作为小微经销商商品建档入口");
        }
        assertTrue(copper.contains("identityReferenceType()"));
        assertTrue(copper.contains("\"磁材牌号\""));
        assertTrue(copper.contains("packagingService.initializeProductProfile"));
        assertTrue(copper.contains("p.identity_status"));
        assertTrue(copper.contains("商品必填规格尚未补齐，不能用于采购、销售、入库或出库"));
        assertTrue(packaging.contains("public PackagingDtos.Profile initializeProductProfile"));
    }

    @Test
    void allComplexMasterWritesHaveExplicitPermissionGates() throws Exception {
        String master = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductMasterDataService.java"));
        String complex = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ComplexProductMasterService.java"));
        for (String source : new String[]{master, complex}) {
            assertTrue(source.contains("product.master.complex.view"));
            assertTrue(source.contains("product.master.complex.manage"));
            assertTrue(source.contains("throw new ForbiddenException"));
        }
        assertTrue(complex.contains("requireQualityManagePermission"));
        assertTrue(complex.contains("\"inventory.quality.view\""));
        assertTrue(complex.contains("\"inventory.quality.manage\""));
        assertTrue(complex.contains("\"product.cost.view\""));
        assertTrue(complex.contains("\"product.supplier.offer.manage\""));
    }
}
