package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImportContractTest {
    @Test
    void migrationRetainsOriginalFileRowsConflictsLineageAndPermanentEvents() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V48__product_master_import_staging.sql"));
        for (String marker : new String[]{
                "product_import_job", "source_file_sha256", "mapping_sha256",
                "configuration_sha256", "uk_product_import_file_mapping",
                "product_import_row", "raw_data_json",
                "normalized_data_json", "CREATE, CREATE_PENDING, SAFE_UPDATE, SKIP, CONFLICT",
                "commit_status", "commit_error", "product_import_conflict",
                "product_import_source_lineage", "normalization_rule", "product_import_event"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceUsesSafeProductMasterPathAndPerRowTransactions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductImportService.java"))
                + Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ProductImportReferenceNormalizer.java"))
                + Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        for (String marker : new String[]{
                "copperService.createProduct", "copperService.updateProduct",
                "PROPAGATION_REQUIRES_NEW", "ROW_COMMIT_FAILED", "targetFingerprint",
                "UNKNOWN_SERIES", "UNKNOWN_UNIT", "UNKNOWN_REFERENCE_VALUE",
                "UNMAPPED_SOURCE_COLUMN", "PACKAGING_BASE_UNIT_MISMATCH",
                "INCOMPLETE_PRODUCT_IDENTITY", "FILE_SPEC_SIGNATURE_COLLISION",
                "evaluateCandidateIdentity", "identity_signature", "identitySchemaVersion",
                "product_identity_rule", "identity_reference_type", "磁材牌号",
                "allowSafeUpdates", "idempotentReplay",
                "product.master.complex.view", "product.master.complex.manage",
                "product.manage", "requireViewPermission()", "requireManagePermission()",
                "requireProductManagePermission()",
                "recoverable", "lease_expires_at",
                "return read ? \"product.master.complex.view\" : \"product.master.complex.manage\"",
                "throw new ForbiddenException"
        }) assertTrue(source.contains(marker), marker);
        assertFalse(source.contains("insert into product\n"), "导入服务不得绕过商品主数据服务直写product");
        assertFalse(source.contains("private String productSignature("),
                "导入不得维护一套脱离正式身份规则的本地签名算法");
    }

    @Test
    void controllerProvidesTemplatePreviewHistoryDecisionAndCommit() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/ProductImportController.java"));
        for (String route : new String[]{
                "/template-info", "/template", "/preview", "/{jobId}",
                "/{jobId}/source-file",
                "/{jobId}/rows/{rowId}/decision", "/{jobId}/commit"
        }) assertTrue(controller.contains(route), route);
        assertTrue(controller.contains("MultipartFile"));
        assertTrue(controller.contains("@RequestParam(required = false) String mapping"));
    }
}
