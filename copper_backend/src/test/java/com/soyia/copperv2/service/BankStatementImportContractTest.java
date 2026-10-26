package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BankStatementImportContractTest {
    @Test
    void migrationRetainsFileRowsDuplicatesSuggestionsAndRuleMatches() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V27__bank_statement_import_and_auto_matching.sql"));
        for (String marker : new String[]{
                "bank_statement_import_batch", "bank_statement_import_row", "source_file_sha256",
                "FILE_EXACT", "FILE_SERIAL", "EXISTING_EXACT", "EXISTING_SERIAL", "unmatched_reason_code",
                "suggested_score", "match_rule_version", "applied_auto_match_rows"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void importServiceExplainsScoringAndNeverForcesAmbiguousMatches() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BankStatementImportService.java"));
        for (String marker : new String[]{
                "AUTO_MATCH_SCORE", "UNIQUE_LEAD", "NO_AMOUNT_CANDIDATE", "DATE_OUT_OF_RANGE",
                "COUNTERPARTY_OR_PURPOSE_MISMATCH", "MULTIPLE_CANDIDATES",
                "CANDIDATE_ALREADY_SUGGESTED", "金额一致 +40", "对方户名相符 +25",
                "用途包含系统单号 +30"
        }) assertTrue(source.contains(marker), marker);
    }

    @Test
    void controllerProvidesTemplatesPreviewHistoryAndIdempotentCommit() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/FinancialCloseController.java"));
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/FinancialCloseService.java"));
        for (String route : new String[]{
                "/bank-reconciliations/import-template", "/statement-imports/preview",
                "/statement-imports/{batchId}", "/statement-imports/{batchId}/commit"
        }) assertTrue(controller.contains(route), route);
        assertTrue(service.contains("if (\"COMPLETED\".equals(batch.get(\"status\")))"));
        assertTrue(service.contains("matchingMethod, matchScore"));
        assertTrue(service.contains("AUTO_MATCH_CONFLICT"));
    }
}
