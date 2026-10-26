package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp12TaxComplianceContractTest {
    @Test
    void migrationCreatesImmutableCredentialEvidenceAndVersionedWorkpapers() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V44__electronic_credential_tax_workpaper.sql"));
        for (String table : new String[]{
                "electronic_credential", "electronic_credential_verification",
                "electronic_credential_relation", "electronic_credential_event",
                "tax_workpaper", "tax_workpaper_line", "tax_workpaper_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "active_sha256", "source_file_sha256", "proof_sha256", "content_sha256",
                "data_cutoff_at", "rule_version", "SUPERSEDED", "tax.compliance.view",
                "tax.compliance.manage", "tax.compliance.review",
                "解析不等同验签验真", "仅供复核，不代表已申报"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceSeparatesParsingVerificationLinkingReviewAndOfficialFiling() throws Exception {
        String credential = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/ElectronicCredentialService.java"));
        for (String marker : new String[]{
                "PENDING_EXTERNAL", "AUTHENTICITY", "SIGNATURE", "MOF_TOOLKIT",
                "TAX_DIGITAL_ACCOUNT", "BANK_VALIDATION_TOOL", "source_file_sha256",
                "proof_sha256", "assertInvoiceMatch", "assertStatementMatch", "RELATION_REVERSE"
        }) assertTrue(credential.contains(marker), marker);
        assertFalse(credential.contains("parseStatus().equals(\"VERIFIED\")"));

        String workpaper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxWorkpaperService.java"));
        for (String marker : new String[]{
                "CN_VAT_WORKPAPER_V1", "dataCutoffAt", "OUTPUT_LEDGER_DIFFERENCE",
                "INPUT_LEDGER_DIFFERENCE", "UNVERIFIED_INVOICE", "ELECTRONIC_INVOICE_EXCEPTION",
                "差异不为零", "不是税务机关申报回执"
        }) assertTrue(workpaper.contains(marker), marker);
        assertFalse(workpaper.contains("submitTaxReturn"));
        assertFalse(workpaper.contains("taxAuthoritySubmit"));
    }

    @Test
    void controllerSecurityExportAndAccountingArchiveCoverErp12() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/TaxComplianceController.java"));
        for (String route : new String[]{
                "/dashboard", "/credentials", "/credentials/import", "/verifications",
                "/link-candidates", "/relations", "/workpapers", "/workpapers/generate", "/review", "/export"
        }) assertTrue(controller.contains(route), route);
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        assertTrue(security.contains("tax.compliance.view"));
        assertTrue(security.contains("tax.compliance.manage"));
        assertTrue(security.contains("tax.compliance.review"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        assertTrue(export.contains("electronic_credential"));
        assertTrue(export.contains("tax_workpaper"));
        String archive = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingArchiveService.java"));
        assertTrue(archive.contains("期间电子凭证"));
        assertTrue(archive.contains("taxWorkpaper"));
        assertTrue(archive.contains("assertStoredFile"));
    }
}
