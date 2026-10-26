package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryCompletionContractTest {
    private final Path migrationPath = Path.of("src/main/resources/db/migration/V8__inventory_completion.sql");

    @Test
    void migrationCreatesAllInventoryDocumentsCostPolicyAuditAndPermissions() throws Exception {
        String sql = Files.readString(migrationPath);
        String v9 = Files.readString(Path.of("src/main/resources/db/migration/V9__purchase_return_inventory_amount_cost.sql"));
        String v10 = Files.readString(Path.of("src/main/resources/db/migration/V10__backfill_legacy_inbound_batch_links.sql"));
        String v11 = Files.readString(Path.of("src/main/resources/db/migration/V11__inventory_opening_cost_alignment.sql"));
        for (String table : new String[]{
                "inventory_cost_policy", "inventory_transfer_order", "inventory_transfer_item",
                "inventory_stocktake_order", "inventory_stocktake_item", "purchase_return_order",
                "purchase_return_item", "inventory_cost_adjustment", "inventory_cost_adjustment_item",
                "inventory_document_event"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String permission : new String[]{
                "inventory.transfer", "inventory.stocktake", "purchase.return", "inventory.cost.adjust"
        }) assertTrue(sql.contains("'" + permission + "'"), permission);
        assertTrue(sql.contains("BATCH_ACTUAL"));
        assertTrue(sql.contains("retroactive_recalculation"));
        assertTrue(sql.contains("idx_outbound_allocation_reservation"));
        assertTrue(v9.contains("inventory_amount_cost"));
        assertTrue(v10.contains("HAVING count(*)=1"));
        assertTrue(v10.contains("inventory_batch_id=b.id"));
        assertTrue(v11.contains("inventory_opening_cost_alignment"));
        assertTrue(v11.contains("不重写历史出入库流水"));
    }

    @Test
    void serviceImplementsDraftPostingCancellationReversalAndNoNegativeStock() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/InventoryService.java"));
        for (String operation : new String[]{
                "createTransfer", "updateTransfer", "dispatchTransfer", "receiveTransfer", "reverseTransfer",
                "createStocktake", "updateStocktake", "confirmStocktake", "reverseStocktake",
                "createPurchaseReturn", "updatePurchaseReturn", "confirmPurchaseReturn", "reversePurchaseReturn",
                "createCostAdjustment", "updateCostAdjustment", "confirmCostAdjustment", "reverseCostAdjustment"
        }) assertTrue(source.contains(operation + "("), operation);
        assertTrue(source.contains("禁止负库存"));
        assertTrue(source.contains("reserved(batch.id()"));
        assertTrue(source.contains("status='IN_TRANSIT'"));
        assertTrue(source.contains("SUPPLIER_PREPAY"));
        assertTrue(source.contains("不能追溯冲正"));
        assertTrue(source.contains("同一批次不能重复出现在一张调拨单中"));
        assertTrue(source.contains("tracked_package_quantity"));
        assertTrue(source.contains("reservedQuantity.compareTo(batchQuantity) > 0"));
        assertTrue(source.contains("source_document_no"));
        assertTrue(source.contains("stocktakeAmount(batch"));
    }

    @Test
    void packagingAndLegacyOutboundKeepQuantityAndCostLedgersAligned() throws Exception {
        String packaging = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/PackagingService.java"));
        String copper = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(packaging.contains("dispatchTransferPackaging"));
        assertTrue(packaging.contains("receiveTransferPackaging"));
        assertTrue(packaging.contains("consumePurchaseReturnPackaging"));
        assertTrue(packaging.contains("reverseStocktakePackaging"));
        assertTrue(copper.contains("batchDraftReservedQuantity"));
        assertTrue(copper.contains("requestedByBatch.merge"));
        assertTrue(copper.contains("amount_cost = greatest(amount_cost - ?"));
        assertTrue(copper.contains("purchase_return_order"));
    }

    @Test
    void dashboardInventoryWarningsProjectReservationFreeAndTransitQuantities() throws Exception {
        String copper = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String reservationProjection = "coalesce(r.reserved_quantity,0) reserved_quantity";
        assertTrue(copper.indexOf(reservationProjection) != copper.lastIndexOf(reservationProjection));
        assertTrue(copper.contains("greatest(b.quantity_available-coalesce(r.reserved_quantity,0),0) free_quantity"));
        assertTrue(copper.contains("coalesce(t.in_transit_quantity,0) in_transit_quantity"));
    }

    @Test
    void fixedPackagesCanSplitWithoutMixingOrderAndInventoryUnits() throws Exception {
        String copper = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/CopperService.java"));
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V17__order_fulfillment_base_quantity_alignment.sql"));
        assertTrue(copper.contains("baseQuantityBySalesItem.merge(salesOrderItemId, plan.baseQuantity()"));
        assertTrue(copper.contains("select coalesce(sum(oi.quantity),0)"));
        assertTrue(copper.contains("salesOrderRequestedEquivalent"));
        assertTrue(copper.contains("purchaseOrderRequestedEquivalent"));
        assertTrue(copper.contains("receivedBase.add(item.quantity()).compareTo(orderedBase) > 0"));
        assertTrue(copper.contains("outboundAddressSnapshot(req.salesOrderId(), req.customerId(), addressId)"));
        assertTrue(copper.contains("validateOutboundSalesItemPrice"));
        assertTrue(copper.contains("关联出库必须沿用销售订单已确认的计价单位和销售单价"));
        assertTrue(copper.contains("销售订单已确认价格，关联出库不能改为待定价"));
        assertTrue(copper.contains("select address_snapshot from sales_order"));
        assertTrue(migration.contains("shipped_quantity * requested_quantity / quantity"));
        assertTrue(migration.contains("received_quantity * requested_quantity / quantity"));
    }

    @Test
    void controllerAndSecurityExposeEveryLifecycleWithLeastPrivilege() throws Exception {
        String controller = Files.readString(Path.of("src/main/java/com/soyia/copperv2/controller/InventoryController.java"));
        String security = Files.readString(Path.of("src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        for (String route : new String[]{"/inventory/transfers", "/inventory/stocktakes",
                "/inventory/purchase-returns", "/inventory/cost-adjustments", "/inventory/movements",
                "/inventory/reconciliation", "/inventory/cost-opening-alignments"})
            assertTrue(controller.contains(route), route);
        for (String action : new String[]{"/dispatch", "/receive", "/confirm", "/cancel", "/reverse"})
            assertTrue(controller.contains(action), action);
        assertTrue(security.contains("inventory.transfer"));
        assertTrue(security.contains("inventory.stocktake"));
        assertTrue(security.contains("purchase.return"));
        assertTrue(security.contains("inventory.cost.adjust"));
        assertTrue(security.contains("INVENTORY_COST_ADJUSTMENT"));
    }
}
