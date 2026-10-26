package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerOperationsDashboardContractTest {
    @Test
    void ownerAttentionUsesAuthoritativeSettlementRiskAndCloseSources() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        for (String source : new String[]{
                "partner_customer_profile", "partner_supplier_profile", "settlement_days",
                "purchase_return_order", "inventory_batch", "stock_balance", "outbound_allocation",
                "bank_statement_line", "bank_reconciliation_match", "bank_reconciliation_difference",
                "tax_invoice_relation", "financialCloseService.closePreview"
        }) assertTrue(service.contains(source), source);
        assertTrue(service.contains("due_date=current_date"));
        assertTrue(service.contains("due_date<current_date"));
        assertTrue(service.contains("s.status in ('DRAFT','REOPENED')"));
        assertTrue(service.contains("relation_layer='GOODS'"));
        assertTrue(service.contains("relation_layer='SETTLEMENT'"));
        assertTrue(service.contains("relation_layer='ACCOUNTING'"));
        assertTrue(service.contains("inventoryService.reconciliation(null, null, false)"));
        String inventory = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/InventoryService.java"));
        assertTrue(inventory.contains("negative_source_count"));
        assertTrue(inventory.contains("unavailable_batch_count"));
        assertTrue(inventory.contains("\"RISK\""));
    }

    @Test
    void restrictedBusinessScopesNeverReceiveGlobalOwnerTotals() throws Exception {
        String scope = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/BusinessDataScopeService.java"));
        assertTrue(scope.contains("source.ownerAttention()"));
        assertTrue(scope.contains("new DashboardDto.OwnerAttention(false"));
        assertTrue(scope.contains("经营待办汇总仅对全量业务数据范围开放"));
    }

    @Test
    void fulfillmentRemindersUseOpenRemainingLinesAndMutuallyExclusiveSevenDayBuckets() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        for (String contract : new String[]{
                "fulfillmentReminders()",
                "o.status in ('ORDERED','PARTIAL_RECEIVED')",
                "o.status in ('OPEN','PARTIAL_SHIPPED')",
                "received_requested_quantity<i.requested_quantity",
                "shipped_requested_quantity<i.requested_quantity",
                "coalesce(o.source_type,'STANDARD')<>'DIRECT_TRADE'",
                "between current_date and date_add(current_date,interval 7 day)",
                "when 0 then 'TODAY'",
                "when 1 then 'TOMORROW'",
                "else 'UPCOMING'"
        }) assertTrue(service.contains(contract), contract);
        String scope = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/BusinessDataScopeService.java"));
        assertTrue(scope.contains("filterDerivedDocumentList(source.fulfillmentReminders())"));
    }
}
