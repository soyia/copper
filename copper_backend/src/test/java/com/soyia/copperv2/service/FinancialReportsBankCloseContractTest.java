package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FinancialReportsBankCloseContractTest {
    @Test
    void migrationCreatesVersionedReportsBankReconciliationCloseEvidenceAndLeastPrivilege() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V20__financial_reports_bank_reconciliation_period_close.sql"));
        for (String table : new String[]{
                "financial_report_line", "financial_report_account_map", "business_bank_account",
                "bank_reconciliation_session", "bank_statement_line", "bank_reconciliation_match",
                "bank_reconciliation_difference", "bank_reconciliation_pending_system",
                "bank_reconciliation_event", "accounting_period_close_run", "accounting_period_close_check"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "余额调节表", "不可覆盖", "active_match_key", "active_source_key",
                "BS_ASSET_TOTAL", "IS_NET_PROFIT",
                "financial.report.view", "financial.bank.manage", "financial.close.manage",
                "RECEIVABLE_ACCEPTANCE", "ACCEPTANCE_DISCOUNT_FEE"
        }) assertTrue(sql.contains(marker), marker);
        String taxMapping = Files.readString(Path.of(
                "src/main/resources/db/migration/V21__financial_report_tax_account_mapping.sql"));
        for (String account : new String[]{"22210101", "22210102", "22210103"}) {
            assertTrue(taxMapping.contains(account), account);
        }
        String bankSource = Files.readString(Path.of(
                "src/main/resources/db/migration/V22__bank_account_source_and_refund_reversal.sql"));
        for (String marker : new String[]{
                "business_bank_account_id", "CUSTOMER_ADVANCE_REFUND_POST",
                "CUSTOMER_ADVANCE_REFUND_REVERSE", "fk_acceptance_event_business_bank"
        }) assertTrue(bankSource.contains(marker), marker);
    }

    @Test
    void serviceImplementsReportsReversibleReconciliationAndHardCloseChecks() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/FinancialCloseService.java"));
        for (String operation : new String[]{
                "balanceSheet", "incomeStatement", "cashFlow", "createReconciliation",
                "saveStatementLines", "createMatch", "reverseMatch", "createDifference",
                "reverseDifference", "createPendingSystem", "reversePendingSystem",
                "voidStatementLine", "confirmReconciliation", "reopenReconciliation",
                "closePreview", "generateProfitVoucher", "closePeriod", "reopenPeriod"
        }) assertTrue(source.contains(operation + "("), operation);
        for (String control : new String[]{
                "PERIOD_END", "PRIOR_PERIOD", "VOUCHER_STATUS", "BUSINESS_POSTING",
                "INVENTORY_DOCUMENTS", "INVENTORY_RECONCILIATION", "PARTNER_BALANCE",
                "ACCEPTANCE_ACCOUNTING", "TAX_INVOICE", "BANK_RECONCILIATION",
                "CASH_FLOW_RECONCILIATION", "TRIAL_BALANCE", "REPORT_MAPPING",
                "BALANCE_SHEET", "PROFIT_CARRY_FORWARD"
        }) assertTrue(source.contains("\"" + control + "\""), control);
        assertTrue(source.contains("validateStatementBalances"));
        assertTrue(source.contains("disposeSourceVoucher"));
        assertTrue(source.contains("不存在未结账的以前期间"));
        assertTrue(source.contains("x.business_bank_account_id=?"));
        assertTrue(source.contains("ensureBankReportMapping"));
        assertTrue(source.contains("!definition.accountCodes().isEmpty()"));
        assertTrue(source.contains("requiredBankAccountIds"));
        assertTrue(source.contains("已有对账记录后不能更换银行账号、总账科目或期初口径"));
        assertTrue(source.contains("lockFundSource"));
        assertTrue(source.contains("银行利息冲减财务费用"));

        String accounting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingService.java"));
        assertTrue(accounting.contains("settlementAccount(entityId, batch)"));
        assertTrue(accounting.contains("accountForSettlement(entityId, \"1001\""));

        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(copper.contains("resolveBusinessBankAccount"));
        assertTrue(copper.contains("ensureFundSourceNotReconciled"));
        assertTrue(copper.contains("reverseCustomerAdvanceRefund"));
    }

    @Test
    void controllerSecurityExportAndLegacyPeriodRoutesUseFormalCloseBoundary() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/FinancialCloseController.java"));
        String accounting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/AccountingController.java"));
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        for (String route : new String[]{
                "/reports/balance-sheet", "/reports/income-statement", "/reports/cash-flow",
                "/bank-accounts", "/bank-reconciliations", "/statement-lines", "/matches",
                "/differences", "/pending-system", "/confirm", "/reopen", "/period-close"
        }) assertTrue(controller.contains(route), route);
        assertTrue(accounting.contains("financialCloseService.closePeriod"));
        assertTrue(accounting.contains("financialCloseService.reopenPeriod"));
        assertTrue(security.contains("financial.close.manage"));
        assertTrue(export.contains("financial-close"));
    }

    @Test
    void realMySqlDirtyDataAcceptanceCannotBeReplacedByStaticMarkers() throws Exception {
        String acceptance = Files.readString(Path.of(
                "src/test/java/com/soyia/copperv2/service/FinancialCloseDirtyDataMySqlAcceptanceTest.java"));
        for (String check : new String[]{
                "VOUCHER_STATUS", "BUSINESS_POSTING", "INVENTORY_DOCUMENTS", "INVENTORY_RECONCILIATION",
                "PARTNER_BALANCE", "TAX_INVOICE", "BANK_RECONCILIATION", "CASH_FLOW_RECONCILIATION",
                "REPORT_MAPPING", "PROFIT_CARRY_FORWARD"
        }) assertTrue(acceptance.contains("\"" + check + "\""), check);
        assertTrue(acceptance.contains("closeService.closePreview(periodId)"));
        assertTrue(acceptance.contains("closeService.closePeriod(periodId"));
        assertTrue(acceptance.contains("@AfterTransaction"));

        String runner = Files.readString(Path.of("tests/run-financial-close-dirty-data-mysql.sh"));
        assertTrue(runner.contains("FinancialCloseDirtyDataMySqlAcceptanceTest"));
        assertTrue(runner.contains("DIRTY_CLOSE_ACCEPTANCE=PASS scenarios=10 rollback=verified"));

        String workflow = Files.readString(Path.of(".github/workflows/publish-m1-image.yml"));
        assertTrue(workflow.contains("dirty-close-acceptance"));
        assertTrue(workflow.contains("tests/run-financial-close-dirty-data-mysql.sh"));
    }
}
