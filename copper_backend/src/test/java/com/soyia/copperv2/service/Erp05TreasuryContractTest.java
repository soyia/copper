package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp05TreasuryContractTest {
    @Test
    void migrationDefinesSeparatedSourceApprovalFundJournalAndAuditFacts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V38__treasury_expense_and_cash_journal.sql"));
        for (String table : new String[]{
                "treasury_document", "treasury_document_event", "treasury_payment_request",
                "treasury_payment_request_event", "treasury_fund_transaction",
                "treasury_cash_journal", "treasury_fund_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "CORPORATE_EXPENSE", "OTHER_EXPENSE", "OTHER_INCOME", "OTHER_RECEIVABLE",
                "OTHER_PAYABLE", "INTERNAL_TRANSFER", "审批不等于付款",
                "资金、发票和凭证分层", "treasury.reverse"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceKeepsAccrualPaymentTaxAccountingAndReconciliationSeparated() throws Exception {
        String treasury = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TreasuryService.java"));
        for (String operation : new String[]{
                "createDocument", "confirmDocument", "createPaymentRequest", "approvePaymentRequest",
                "settleDocument", "createTransfer", "reverseFund", "journal"
        }) assertTrue(treasury.contains(operation + "("), operation);
        assertTrue(treasury.contains("源单收付不能使用内部转账分类"));
        assertTrue(treasury.contains("资金已经进入银行对账"));
        assertTrue(treasury.contains("只有已确认且尚未结清的源单可以申请付款"));
        assertTrue(treasury.contains("document.totalAmount(), ZERO"), "费用源单应先按价税合计入账，不能提前重复拆税");

        String invoice = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxInvoiceService.java"));
        assertTrue(invoice.contains("treasuryTaxRelations"));
        assertTrue(invoice.contains("TAX_INVOICE_TREASURY_RECLASS"));
        assertTrue(invoice.contains("TAX_INVOICE_TREASURY_UNMATCH"));
        assertTrue(invoice.contains("该勾稽层累计税额不能超过发票税额"));
        assertTrue(invoice.contains("concat(document_type,':',status)"));

        String close = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/FinancialCloseService.java"));
        assertTrue(close.contains("TREASURY_DOCUMENTS"));
        assertTrue(close.contains("treasury_fund_transaction"));
        assertTrue(close.contains("treasury_payment_request"));
        assertTrue(close.contains("TREASURY_JOURNAL"));
    }
}
