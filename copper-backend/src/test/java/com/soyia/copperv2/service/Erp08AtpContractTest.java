package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp08AtpContractTest {
    @Test
    void migrationFreezesCoordinatesConcurrencyLockAndImmutableDecisionSnapshots() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V42__inventory_atp_commitment.sql"));
        for (String marker : new String[]{
                "fulfillment_warehouse_id", "expected_warehouse_id", "expected_arrival_date",
                "CREATE TABLE inventory_atp_lock (", "PRIMARY KEY (product_id,warehouse_id)",
                "CREATE TABLE inventory_atp_snapshot (", "TIME_PHASED_ATP_V1",
                "CONFIRM','AMEND','CANCEL", "PROMISED','RELEASED"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceUsesTimePhasedAtpWithoutDoubleCountingLinkedOutboundDrafts() throws Exception {
        String atp = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AtpService.java"));
        for (String marker : new String[]{
                "TIME_PHASED_ATP_V1", "inventory_atp_lock", "for update",
                "linked_draft_covered_quantity", "sales.subtract(covered)",
                "PURCHASE_INCOMING", "TRANSFER_INCOMING", "UNALLOCATED_LEGACY_COMMITMENT",
                "timePhaseCheck", "recordSnapshots", "Isolation.READ_COMMITTED"
        }) assertTrue(atp.contains(marker), marker);
        assertFalse(atp.contains("while (rs.next())"), "RowCallbackHandler already points at the current bucket");

        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(copper.indexOf("atpService.enforceSalesOrder(salesOrderId, \"CONFIRM\"")
                < copper.indexOf("update sales_order set status = 'OPEN'"));
        assertTrue(copper.contains("atpService.lockSalesOrderAmendment"));
        assertTrue(copper.indexOf("salesRiskService.lockCustomerScope")
                < copper.indexOf("atpService.lockSalesOrderAmendment"));
        assertTrue(copper.contains("atpService.recordSalesOrderRelease"));
        assertTrue(copper.contains("@Transactional(isolation = Isolation.READ_COMMITTED)"));
        assertTrue(copper.contains("出库仓库与销售订单履约仓库不一致"));
        assertTrue(copper.contains("入库仓库与采购订单预计入库仓库不一致"));
    }

    @Test
    void apiSeparatesAtpSummaryTimelineOrderPreviewAndAuditHistory() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/AtpController.java"));
        for (String path : new String[]{
                "/inventory/atp", "/inventory/atp/timeline",
                "/sales-orders/{id}/atp", "/sales-orders/{id}/atp-history"
        }) assertTrue(controller.contains(path), path);
    }
}
