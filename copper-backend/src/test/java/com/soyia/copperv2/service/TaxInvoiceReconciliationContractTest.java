package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxInvoiceReconciliationContractTest {
    @Test
    void migrationCreatesIndependentInvoicesSnapshotsReconciliationAndAudit() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V19__tax_invoice_reconciliation.sql"));
        for (String table : new String[]{
                "tax_rate_rule", "tax_invoice", "tax_invoice_line", "tax_invoice_relation",
                "tax_invoice_event", "tax_invoice_use_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "发票、业务、资金和会计凭证分层", "历史发票只使用快照",
                "CONTRACT, GOODS, SETTLEMENT, FUNDS, ACCOUNTING",
                "red_confirmation_expires_at", "blue_invoice_id", "source_file_sha256",
                "22210101", "22210102", "22210103"
        }) assertTrue(sql.contains(marker), marker);
        for (String permission : new String[]{
                "invoice.view", "invoice.manage", "invoice.confirm", "invoice.verify", "invoice.red.manage"
        }) assertTrue(sql.contains("'" + permission + "'"), permission);
    }

    @Test
    void serviceSeparatesInvoiceConfirmationVerificationUsageRedFlowAndLayerMatching() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxInvoiceService.java"));
        for (String operation : new String[]{
                "saveInvoice", "confirmInvoice", "voidInvoice", "verifyInvoice", "confirmBooking",
                "confirmUsage", "transferOut", "updateRedConfirmation", "sourceCandidates",
                "addRelation", "reverseRelation", "createBaseVoucher"
        }) assertTrue(source.contains(operation + "("), operation);
        assertTrue(source.contains("发票确认不等于收付款或业务确认"));
        assertTrue(source.contains("确认单发起后72小时"));
        assertTrue(source.contains("累计红冲金额、税额或价税合计"));
        assertTrue(source.contains("该勾稽层累计金额不能超过发票价税合计"));
        assertTrue(source.contains("m.direction='DEBIT'"));
        assertTrue(source.contains("m.direction='CREDIT'"));
        assertTrue(source.contains("DECREASE:PURCHASE_RETURN:CONFIRMED"));
        assertTrue(source.contains("CREDIT:SALES_RETURN:CONFIRMED"));
        assertTrue(source.contains("select sku,spec from product"));
        assertTrue(source.contains("TAX_INVOICE_BASE"));
        assertTrue(source.contains("TAX_INVOICE_USAGE"));
        assertTrue(source.contains("TAX_INVOICE_TRANSFER"));
        assertTrue(source.contains("inputRedDispositionVoucher"));
        assertTrue(source.contains("本红票不重复生成分录"));
        assertTrue(source.contains("ACCOUNTING_RELATION_REPAIR"));
        assertTrue(source.contains("RED_USAGE_DERIVE"));
        assertTrue(source.contains("initialUsageStatus(direction, color)"));
        String accounting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingService.java"));
        assertTrue(accounting.contains("LinkedTaxInvoiceView"));
        assertTrue(accounting.contains("tax_invoice_relation r join tax_invoice i"));
    }

    @Test
    void controllerSecurityAndExportsCoverTheTaxDomain() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/TaxInvoiceController.java"));
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        for (String route : new String[]{
                "/dashboard", "/rates", "/confirm", "/void", "/verify", "/booking-confirm",
                "/usage-confirm", "/usage-transfer-out", "/red-confirmation",
                "/source-candidates", "/relations"
        }) assertTrue(controller.contains(route), route);
        assertTrue(security.contains("invoice.red.manage"));
        assertTrue(security.contains("invoice.verify"));
        assertTrue(security.contains("TAX_INVOICE"));
        assertTrue(export.contains("tax-invoices"));
        assertTrue(export.contains("accounting"));
    }
}
