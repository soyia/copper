package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementReportContractTest {
    @Test
    void backendContractUsesOneSnapshotFullAggregationAgingAndServerPagination() throws Exception {
        String service = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/SettlementReportService.java"));
        String controller = Files.readString(Path.of("src/main/java/com/soyia/copperv2/controller/SettlementReportController.java"));
        String dataScope = Files.readString(Path.of("src/main/java/com/soyia/copperv2/security/BusinessDataScopeService.java"));

        assertTrue(controller.contains("/settlement-reports/receivables"));
        assertTrue(controller.contains("/settlement-reports/payables"));
        assertTrue(service.contains("Isolation.REPEATABLE_READ"));
        assertTrue(service.contains("current_timestamp(6)"));
        assertTrue(service.contains("count(distinct x.partner_id)"));
        assertTrue(service.contains("sum(x.unpaid_amount)"));
        assertTrue(service.contains("group by x.aging_bucket"));
        assertTrue(service.contains("limit ? offset ?"));
        assertTrue(service.contains("MAX_PAGE_SIZE = 200"));
        assertTrue(service.contains("o.settlement_days_snapshot settlement_days"));
        assertTrue(service.contains("o.due_date"));
        assertFalse(service.contains("partner_customer_profile"));
        assertFalse(service.contains("partner_supplier_profile"));
        assertTrue(dataScope.contains("Aggregated settlement reports cannot be corrected by response-list filtering"));
        assertTrue(dataScope.contains("documentScope().all()"));
    }

}
