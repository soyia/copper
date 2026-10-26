package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentAllocationFoundationContractTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V32__payment_allocation_foundation.sql");
    private static final Path SERVICE = Path.of(
            "src/main/java/com/soyia/copperv2/service/CopperService.java");
    private static final Path COMPLETE_MIGRATION = Path.of(
            "src/main/resources/db/migration/V33__complete_payment_allocation.sql");
    private static final Path ACCOUNTING_MIGRATION = Path.of(
            "src/main/resources/db/migration/V34__payment_allocation_accounting.sql");

    @Test
    void migrationCreatesAuditableAllocationLinesAndBackfillsLegacyTargets() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertTrue(sql.contains("CREATE TABLE payment_allocation"));
        assertTrue(sql.contains("CREATE TABLE supplier_payment_allocation"));
        assertTrue(sql.contains("allocated_amount decimal(18,2) NOT NULL"));
        assertTrue(sql.contains("capture_source varchar(32) NOT NULL DEFAULT 'CREATION'"));
        assertTrue(sql.contains("capture_source IN ('CREATION', 'LEGACY_BACKFILL')"));
        assertTrue(sql.contains("LEGACY_BACKFILL"));
        assertTrue(sql.contains("WHEN coalesce(p.outbound_id, 0) > 0 THEN 'OUTBOUND'"));
        assertTrue(sql.contains("WHEN coalesce(p.inbound_id, 0) > 0 THEN 'INBOUND'"));
        assertTrue(sql.contains("WHEN p.verification_status = 'REVERSED' THEN 'REVERSED'"));
        assertTrue(sql.contains("WHERE coalesce(p.outbound_id, 0) > 0 OR coalesce(p.sales_order_id, 0) > 0"));
        assertTrue(sql.contains("WHERE coalesce(p.inbound_id, 0) > 0 OR coalesce(p.purchase_order_id, 0) > 0"));
    }

    @Test
    void completeApiKeepsLegacySingleTargetAndAddsExplicitMultiTargetContract() throws Exception {
        String source = Files.readString(SERVICE);
        String receiptRequest = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/dto/PaymentCreateRequest.java"));
        String supplierRequest = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/dto/SupplierPaymentCreateRequest.java"));

        assertTrue(source.contains("insert into payment_allocation"));
        assertTrue(source.contains("paymentNo + \"-01\""));
        assertTrue(source.contains("String targetType = outboundId > 0 ? \"OUTBOUND\" : \"SALES_ORDER\""));
        assertTrue(source.contains("insert into supplier_payment_allocation"));
        assertTrue(source.contains("String targetType = inboundId > 0 ? \"INBOUND\" : \"PURCHASE_ORDER\""));
        assertTrue(receiptRequest.contains("allocations"));
        assertTrue(supplierRequest.contains("allocations"));
        assertTrue(source.contains("createAllocatedPayment(req)"));
        assertTrue(source.contains("createAllocatedSupplierPayment(req)"));
        assertTrue(source.contains("changePaymentAllocations"));
        assertTrue(source.contains("changeSupplierPaymentAllocations"));
        assertTrue(source.contains("unallocated_amount"));
    }

    @Test
    void completionMigrationAndReversalPreserveEveryAllocationAuditEvent() throws Exception {
        String source = Files.readString(SERVICE);
        String sql = Files.readString(COMPLETE_MIGRATION);

        assertTrue(sql.contains("CREATE TABLE payment_allocation_event"));
        assertTrue(sql.contains("CREATE TABLE supplier_payment_allocation_event"));
        assertTrue(sql.contains("'REALLOCATION'"));
        assertTrue(sql.contains("'RELEASED'"));
        assertTrue(source.contains("HEAD_REVERSED"));
        assertTrue(source.contains("有效分配合计与收款头金额不一致"));
        assertTrue(source.contains("有效分配合计与付款头金额不一致"));
        assertTrue(source.contains("ensureFundSourceNotTaxMatched"));
    }

    @Test
    void unallocatedOrdinaryFundsAndLaterAllocationHaveSeparateAccountingFacts() throws Exception {
        String source = Files.readString(SERVICE);
        String sql = Files.readString(ACCOUNTING_MIGRATION);

        assertTrue(sql.contains("UNALLOCATED_CUSTOMER_RECEIPT"));
        assertTrue(sql.contains("UNALLOCATED_SUPPLIER_PAYMENT"));
        assertTrue(sql.contains("CUSTOMER_RECEIPT_ALLOCATION"));
        assertTrue(sql.contains("SUPPLIER_PAYMENT_ALLOCATION"));
        assertTrue(sql.contains("'ALLOCATION_CHANGE'"));
        assertTrue(source.contains("customerPaymentPostingEntries"));
        assertTrue(source.contains("supplierPaymentPostingEntries"));
        assertTrue(source.contains("postCustomerAllocationChange"));
        assertTrue(source.contains("postSupplierAllocationChange"));
        assertTrue(source.contains("List.of(\"POST\", \"ALLOCATION_CHANGE\")"));
    }
}
