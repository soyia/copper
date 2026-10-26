package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationalLiteSuiteContractTest {
    @Test
    void migrationKeepsAllFourLiteCapabilitiesAuditableAndOutsideAutomaticPosting() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V58__operational_lite_suite.sql"));
        for (String token : List.of(
                "CREATE TABLE purchase_followup_event (",
                "CREATE TABLE purchase_followup_event_item (",
                "CREATE TABLE external_confirmation_packet (",
                "CREATE TABLE external_confirmation_record (",
                "CREATE TABLE document_intake_batch (",
                "CREATE TABLE document_intake_candidate (",
                "snapshot_hash",
                "REVIEW_REQUIRED",
                "purchase.followup.manage",
                "external.confirm.manage",
                "document.intake.manage")) {
            assertTrue(migration.contains(token), token);
        }
    }

    @Test
    void servicePreservesInventorySettlementAndDocumentCandidateBoundaries() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/OperationalLiteSuiteService.java"));
        for (String token : List.of(
                "直运采购必须",
                "应收只是预计流入",
                "不能覆盖原确认记录",
                "existingIntake(type, digest)",
                "refreshExistingPackageLabelMatch",
                "DIRECT_TRADE",
                "snapshot_hash")) {
            assertTrue(service.contains(token), token);
        }
        assertTrue(!service.contains("该类型的同一原始文件已经上传，无需重复识别"));
        assertTrue(!service.contains("update stock_balance"));
        assertTrue(!service.contains("insert into payment_allocation"));
        assertTrue(!service.contains("insert into accounting_voucher"));
    }

    @Test
    void publicConfirmationEndpointsRemainExplicitlyOutsideSessionAuthentication() throws Exception {
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        assertTrue(security.contains("path.startsWith(\"/public/confirmations/\")"));
        assertTrue(security.contains("external.confirm.manage"));
        assertTrue(security.contains("document.intake.manage"));
        assertTrue(security.contains("purchase.followup.manage"));
    }

    @Test
    void packageLabelOcrKeepsOriginalAuditAndNeverTreatsQrAsProductIdentity() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V60__package_label_ocr_mvp.sql"));
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/OperationalLiteSuiteService.java"));
        String parser = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/HongtaiPackageLabelParser.java"));
        String matcher = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PackageLabelProductMatcher.java"));
        String ocrClient = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/PaddleOcrClient.java"));
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/OperationalLiteSuiteController.java"));
        for (String token : List.of(
                "PACKAGE_LABEL", "ocr_engine", "ocr_model", "ocr_response_sha256")) {
            assertTrue(migration.contains(token), token);
        }
        for (String token : List.of(
                "HONGTAI_PACKAGE_LABEL_V1", "product_name", "specification",
                "actual_weight_kg", "production_date", "package_code")) {
            assertTrue(parser.contains(token), token);
        }
        assertTrue(!service.toLowerCase().contains("qr_payload"));
        assertTrue(!parser.toLowerCase().contains("qr_payload"));
        assertTrue(matcher.contains("DIRECT_DIMENSION_PAIR"));
        assertTrue(matcher.contains(".replace(\"紫铜\", \"\")"));
        assertTrue(!matcher.toLowerCase().contains("qr"));
        assertTrue(ocrClient.contains("QR"));
        assertTrue(!ocrClient.contains("BarcodeDetector"));
        assertTrue(controller.contains(
                "@RequestParam(value = \"expectedProductId\", required = false) Long expectedProductId"));
    }
}
