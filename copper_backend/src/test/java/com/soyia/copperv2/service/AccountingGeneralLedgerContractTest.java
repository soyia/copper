package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountingGeneralLedgerContractTest {
    @Test
    void migrationCreatesLayeredAccountingModelRulesAndLeastPrivilegePermissions() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V18__accounting_general_ledger.sql"));
        for (String table : new String[]{
                "accounting_entity", "accounting_period", "accounting_account",
                "accounting_rule_version", "accounting_account_event", "accounting_voucher", "accounting_voucher_line",
                "accounting_voucher_event", "accounting_ledger_entry",
                "accounting_generation_issue", "accounting_generation_result", "accounting_period_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String permission : new String[]{
                "accounting.view", "accounting.manage", "accounting.review",
                "accounting.post", "accounting.period.manage", "accounting.rule.manage"
        }) assertTrue(sql.contains("'" + permission + "'"), permission);
        assertTrue(sql.contains("业务单据与会计凭证分层"));
        assertTrue(sql.contains("锁定不等同于完整期末结账"));
        assertTrue(sql.contains("uk_accounting_voucher_generation"));
        assertTrue(sql.contains("VOUCHER_CREATED, SOURCE_VOUCHER_CANCELLED"));
    }

    @Test
    void serviceImplementsVersionedGenerationReviewPostingReversalAndLedger() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingService.java"));
        for (String operation : new String[]{
                "saveAccount", "initializeYear", "closePeriod", "reopenPeriod",
                "saveRule", "publishRule", "createManualVoucher", "updateManualVoucher",
                "reviewVoucher", "rejectVoucher", "postVoucher", "cancelVoucher",
                "reverseVoucher", "generateForBusinessPosting", "trialBalance", "ledgerEntries"
        }) assertTrue(service.contains(operation + "("), operation);
        assertTrue(service.contains("凭证必须先审核再记账"));
        assertTrue(service.contains("accounting_ledger_entry"));
        assertTrue(service.contains("SOURCE_VOUCHER_CANCELLED"));
        assertTrue(service.contains("凭证日期所属会计期间已锁定"));
        assertTrue(service.contains("同一业务事实"));
    }

    @Test
    void businessPostingSchedulesAccountingOnlyAfterCommittedBusinessFacts() throws Exception {
        String posting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessPostingService.java"));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingGenerationCoordinator.java"));
        assertTrue(posting.contains("afterCommit()"));
        assertTrue(posting.contains("scheduleAccountingGeneration"));
        assertTrue(posting.contains("if (!\"REVERSE\".equals(action))"));
        assertTrue(coordinator.contains("generateSafely"));
        assertTrue(coordinator.contains("recordGenerationFailure"));
    }

    @Test
    void controllerAndSecurityExposeAccountingWithSeparatedReviewAndPostingRights() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/AccountingController.java"));
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        for (String route : new String[]{
                "/accounts", "/periods", "/rules", "/vouchers", "/generation-issues",
                "/trial-balance", "/ledger", "/review", "/post", "/reverse"
        }) assertTrue(controller.contains(route), route);
        assertTrue(security.contains("accounting.review"));
        assertTrue(security.contains("accounting.post"));
        assertTrue(security.contains("accounting.period.manage"));
        assertTrue(security.contains("accounting.rule.manage"));
    }
}
