package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElectronicEvidenceArchiveContractTest {
    @Test
    void migrationCreatesEvidenceVerificationImportAndArchiveAuditTables() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V26__electronic_evidence_and_accounting_archive.sql"));
        for (String table : new String[]{
                "business_evidence_attachment", "tax_invoice_verification_record",
                "tax_invoice_import_batch", "accounting_archive_batch"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "proof_attachment_id", "source_file_sha256", "archive_sha256",
                "evidence.view", "evidence.manage", "accounting.archive",
                "SUPERSEDED", "superseded_reason", "确认后只能追加或留痕作废"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void evidenceCoversSignatureDocumentsAndConfirmedFilesCannotBeDeleted() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/EvidenceAttachmentService.java"));
        for (String document : new String[]{
                "INBOUND", "OUTBOUND", "PURCHASE_ORDER", "SALES_ORDER", "SALES_RETURN",
                "PAYMENT", "SUPPLIER_PAYMENT", "INVENTORY_TRANSFER", "INVENTORY_STOCKTAKE",
                "PURCHASE_RETURN", "INVENTORY_COST_ADJUSTMENT", "INVENTORY_NON_TRADE",
                "TAX_INVOICE", "ACCOUNTING_VOUCHER"
        }) assertTrue(source.contains("Map.entry(\"" + document + "\""), document);
        for (String document : new String[]{"BANK_RECONCILIATION", "PERIOD_CLOSE"})
            assertTrue(source.contains("Map.entry(\"" + document + "\""), document);
        assertTrue(source.contains("\"DRAFT\".equals(identity.status())"));
        assertFalse(source.contains("Set.of(\"DRAFT\", \"REJECTED\")"));
        assertTrue(source.contains("附件不能删除；如凭据有误请留痕作废后补传正确文件"));
        assertTrue(source.contains("VERIFICATION_PROOF"));
        assertTrue(source.contains("SIGNED_STAMPED_COPY"));
        assertFalse(source.contains("documentUnrestricted"));
    }

    @Test
    void documentPrintingIsAuditedWithIdentityStatusTemplateAndSnapshotHash() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V28__document_print_archive_audit.sql"));
        assertTrue(sql.contains("CREATE TABLE business_document_output_log"));
        for (String marker : new String[]{"document_no_snapshot", "status_snapshot", "template_version",
                "snapshot_hash", "不可覆盖审计记录", "SIGNED_STAMPED_COPY"}) assertTrue(sql.contains(marker), marker);

        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/EvidenceController.java"));
        assertTrue(controller.contains("/{documentType}/{documentId}/outputs"));
        assertTrue(controller.contains("ContentDisposition.inline()"));

        String exportCatalog = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        assertTrue(exportCatalog.contains("business_document_output_log"));
    }

    @Test
    void importAndArchiveRetainSourcesWithoutClaimingAutomaticLegalCompliance() throws Exception {
        String importer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxInvoiceImportService.java"));
        assertTrue(importer.contains("导入仅生成草稿"));
        assertTrue(importer.contains("source_file_sha256"));
        assertTrue(importer.contains("blue_invoice_id"));
        assertTrue(importer.contains("taxRateRuleId"));

        String archive = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingArchiveService.java"));
        assertTrue(archive.contains("记账凭证.html"));
        assertTrue(archive.contains("记账凭证.json"));
        assertTrue(archive.contains("SHA256SUMS"));
        assertTrue(archive.contains("addTaxInvoiceImportSource"));
        assertTrue(archive.contains("periodClosedArchive"));
        assertFalse(archive.contains("legalArchiveReady"));
    }
}
