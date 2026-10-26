package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp06LandedCostContractTest {
    @Test
    void migrationDefinesSourceAllocationBatchComponentsPostingAndAuditFacts() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V39__purchase_landed_cost_allocation.sql"));
        for (String table : new String[]{
                "landed_cost_allocation", "landed_cost_allocation_item", "landed_cost_allocation_batch",
                "inventory_batch_landed_cost_component", "landed_cost_allocation_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String marker : new String[]{
                "PURCHASE_LANDED_COST", "QUANTITY", "WEIGHT", "AMOUNT", "MANUAL",
                "LANDED_COST_CAPITALIZED", "LANDED_COST_RETURN_EXPENSE", "ROLLBACK",
                "landed.cost.view", "landed.cost.manage", "landed.cost.confirm", "landed.cost.reverse",
                "timestamp(6)"
        }) assertTrue(sql.contains(marker), marker);
        String taxSql = Files.readString(Path.of(
                "src/main/resources/db/migration/V40__landed_cost_input_tax_treatment.sql"));
        assertTrue(taxSql.contains("input_tax_treatment"));
        assertTrue(taxSql.contains("DEDUCTIBLE"));
        assertTrue(taxSql.contains("INCLUDED_IN_COST"));
    }

    @Test
    void servicesKeepFeeTaxInventoryReturnAndReverseBoundariesSeparated() throws Exception {
        String landed = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/LandedCostService.java"));
        for (String operation : new String[]{"options", "allocations", "allocation", "create", "update", "confirm", "cancel", "reverse"})
            assertTrue(landed.contains(operation + "("), operation);
        for (String marker : new String[]{
                "amount_excluding_tax", "历史已耗用成本不改写",
                "存在调拨在途数量", "按当前结存回退", "assertNoLaterDependentDocuments",
                "a.status in ('DRAFT','CONFIRMED')", "assertMasterAllowed(\"WAREHOUSE\", batch.warehouseId())"
        }) assertTrue(landed.contains(marker), marker);

        String inventory = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/InventoryService.java"));
        for (String marker : new String[]{
                "inventory_batch_landed_cost_component", "landed_cost_amount",
                "LANDED_COST_RETURN_EXPENSE", "采购退货将附加费用转回期间成本",
                "采购退货确认后存在采购费用分摊", "now(6)"
        }) assertTrue(inventory.contains(marker), marker);

        String treasury = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TreasuryService.java"));
        assertTrue(treasury.contains("PURCHASE_LANDED_COST"));
        assertTrue(treasury.contains("采购附加费用已经计入批次成本"));
        assertTrue(treasury.contains("采购附加费用必须选择主营业务成本科目 5401"));

        String invoice = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxInvoiceService.java"));
        assertTrue(invoice.contains("不能再确认为可抵扣进项税"));
        assertTrue(invoice.contains("TreasuryTaxRelation::includedInCost"));
        assertTrue(invoice.contains("税额计入存货成本的发票用途已确认"));

        String close = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/FinancialCloseService.java"));
        assertTrue(close.contains("a.status='CONFIRMED'"));

        String posting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessPostingService.java"));
        assertTrue(posting.contains("b.posting_action='POST' and b.status in ('POSTED','REVERSED')"));
    }
}
