package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp15RiskExceptionContractTest {
    @Test
    void migrationServiceApiAndExportKeepTheLightweightApprovalBoundary() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V45__risk_exception_approval.sql"));
        for (String token : new String[]{"risk_exception_policy", "risk_exception_policy_version",
                "risk_exception_request", "risk_exception_event", "active_request_key",
                "risk.exception.approve", "approval_valid_hours"}) assertTrue(migration.contains(token));

        String service = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/RiskExceptionService.java"));
        for (String token : new String[]{"authorizeOrConsume", "source_snapshot_hash", "assertTriggerMatches",
                "SUPPLIER_PAYMENT_INTENT", "HIGH_RISK_REVERSAL", "INVENTORY_ADJUSTMENT",
                "insert ignore into risk_exception_policy", "businessDateColumn = supplier ? \"paid_date\" : \"received_date\""})
            assertTrue(service.contains(token));

        String controller = Files.readString(Path.of("src/main/java/com/soyia/copperv2/controller/RiskExceptionController.java"));
        for (String token : new String[]{"/requests", "/approve", "/reject", "/withdraw", "/transfer", "/execute"})
            assertTrue(controller.contains(token));

        String catalog = Files.readString(Path.of("src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        assertTrue(catalog.contains("risk_exception_"));
        assertTrue(catalog.contains("CONSUMED"));
    }
}
