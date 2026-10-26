package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp18DataMigrationContractTest {
    @Test
    void migrationWorkbookStateMachineRelationshipsAndCutoverChecksStayExplicit() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V55__opening_data_migration_cutover.sql"));
        for (String token : new String[]{"data_migration_batch", "data_migration_row", "data_migration_event",
                "data_migration_check_run", "data_migration_check_issue", "data_migration_fund_opening",
                "data_migration_row_id", "data_migration_batch_id", "PREFLIGHT_PASSED", "CUTOVER",
                "ROLLED_BACK", "data.migration.cutover"}) assertTrue(migration.contains(token));

        String workbook = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/OpeningMigrationWorkbook.java"));
        for (String token : new String[]{"往来单位", "仓库", "商品", "库存期初", "往来期初", "资金期初",
                "总账期初", "来源键*", "MAX_TOTAL_ROWS", "规格属性JSON", "确认切换后不得整批无痕回滚"})
            assertTrue(workbook.contains(token));

        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/DataMigrationService.java"));
        for (String token : new String[]{"assertEmptyOperationalFacts", "assertNoDownstreamUsage", "runCheck",
                "INVENTORY_OPENING_AMOUNT", "PARTNER_ACCOUNT_LOG_BALANCE", "LEDGER_POSTING_COMPLETE",
                "SUPPLIER_PREPAY_GL", "CUSTOMER_ADVANCE_GL", "reviewVoucher", "postVoucher",
                "data-migration.consistency-cron"}) assertTrue(service.contains(token));

        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/DataMigrationController.java"));
        for (String token : new String[]{"/template", "/upload", "/apply", "/rollback", "/cutover",
                "/consistency-check"}) assertTrue(controller.contains(token));

        String treasury = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TreasuryService.java"));
        assertTrue(treasury.contains("OPENING_RECEIVABLE"));
        assertTrue(treasury.contains("OPENING_PAYABLE"));

        String settlement = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/SettlementReportService.java"));
        assertTrue(settlement.contains("OPENING_RECEIVABLE"));
        assertTrue(settlement.contains("OPENING_PAYABLE"));

        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        assertTrue(export.contains("data_migration_"));
    }
}
