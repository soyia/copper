package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerFundAdjustmentContractTest {
    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V35__partner_fund_adjustment.sql");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/soyia/copperv2/service/PartnerFundAdjustmentService.java");

    @Test
    void formalSourceDocumentReplacesTheDirectBalanceWriteEndpoint() throws Exception {
        String migration = Files.readString(MIGRATION);
        String service = Files.readString(SERVICE);
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/CopperController.java"));
        String adjustmentController = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/PartnerFundAdjustmentController.java"));

        assertTrue(migration.contains("CREATE TABLE partner_fund_adjustment"));
        assertTrue(migration.contains("CREATE TABLE partner_fund_adjustment_event"));
        assertTrue(migration.contains("仅保留查询审计，禁止继续写入"));
        assertFalse(controller.contains("@PostMapping(\"/partner-account-actions\")"));
        assertTrue(adjustmentController.contains("@RequestMapping(\"/partner-fund-adjustments\")"));
        assertFalse(adjustmentController.contains("@RequestMapping(\"/api/partner-fund-adjustments\")"));
        assertFalse(service.contains("insert into partner_account_action"));
        assertTrue(service.contains("PARTNER_FUND_ADJUSTMENT_REVERSAL"));
        assertTrue(service.contains("partner_account_log"));
    }

    @Test
    void sourceTypesHaveNarrowSemanticsAndNoCashOrTaxSideEffects() throws Exception {
        String migration = Files.readString(MIGRATION);
        String service = Files.readString(SERVICE);

        for (String type : new String[]{"OPENING_BALANCE", "CUSTOMER_REWARD_GRANT",
                "SUPPLIER_REBATE_GRANT", "BALANCE_CORRECTION"}) {
            assertTrue(service.contains(type), type);
        }
        assertTrue(service.contains("期初余额只能用于尚无余额和流水的账户"));
        assertTrue(service.contains("已有订单占用"));
        assertTrue(service.contains("PENDING_PROFESSIONAL_REVIEW"));
        assertTrue(migration.contains("\"cashImpact\":false"));
        assertTrue(migration.contains("\"taxImpact\":false"));
        assertTrue(migration.contains("税票另行勾稽"));
    }

    @Test
    void approvalPostingAccountingReversalCloseAndExportAreAllRegistered() throws Exception {
        String migration = Files.readString(MIGRATION);
        String posting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessPostingService.java"));
        String close = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/FinancialCloseService.java"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportArchiveWriter.java"));

        for (String permission : new String[]{"partner.fund.adjust.view", "partner.fund.adjust.manage",
                "partner.fund.adjust.approve", "partner.fund.adjust.reverse"}) {
            assertTrue(migration.contains(permission), permission);
        }
        assertTrue(migration.contains("PARTNER_FUND_ADJUSTMENT_POST"));
        assertTrue(migration.contains("PARTNER_FUND_ADJUSTMENT_REVERSE"));
        assertTrue(posting.contains("已确认余额调整缺少业务过账"));
        assertTrue(close.contains("PARTNER_FUND_ADJUSTMENTS"));
        assertTrue(close.contains("from partner_account_log"));
        assertTrue(export.contains("partner_fund_adjustment.amount."));
    }

    @Test
    void balanceSettlementUsesAdvanceOrRewardAccountsInsteadOfFakeBankCash() throws Exception {
        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String migration = Files.readString(MIGRATION);

        for (String fact : new String[]{"CUSTOMER_ADVANCE_SETTLEMENT", "CUSTOMER_REWARD_SETTLEMENT",
                "SUPPLIER_PREPAY_SETTLEMENT", "SUPPLIER_REWARD_SETTLEMENT"}) {
            assertTrue(copper.contains(fact), fact);
            assertTrue(migration.contains(fact), fact);
        }
        assertTrue(migration.contains("'2203','1122'"));
        assertTrue(migration.contains("'2202','1123'"));
    }
}
