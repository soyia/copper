package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Erp07SalesRiskContractTest {
    @Test
    void migrationFreezesPolicyVersionsDecisionSnapshotsAndOwnerOnlyOverrides() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V41__sales_credit_margin_control.sql"));
        for (String marker : new String[]{
                "CREATE TABLE sales_risk_policy (", "CREATE TABLE sales_risk_policy_version (",
                "CREATE TABLE sales_risk_snapshot (", "credit_limit_snapshot", "margin_available",
                "issue_codes_json", "OVERRIDDEN", "timestamp(6)",
                "sales.risk.policy.manage", "sales.risk.override", "('ADMIN','OWNER')"
        }) assertTrue(sql.contains(marker), marker);
    }

    @Test
    void serviceUsesOneExposureDefinitionAndEnforcesOrderAmendmentAndShipment() throws Exception {
        String risk = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/SalesRiskService.java"));
        for (String marker : new String[]{
                "receivable_amount-received_amount-adjusted_amount",
                "status in ('OPEN','PARTIAL_SHIPPED')", "CUSTOMER_ADVANCE", "acceptance_bill",
                "CURRENT_STOCK_WEIGHTED_AVERAGE_V1", "BATCH_ACTUAL_COST_V1",
                "CREDIT_LIMIT_EXCEEDED", "OVERDUE_RECEIVABLE", "BELOW_MINIMUM_MARGIN",
                "MANUAL_PRICE_BELOW_SUGGESTED", "riskExceptionService.authorizeOrConsume", "recordSnapshot"
        }) assertTrue(risk.contains(marker), marker);

        String exception = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/RiskExceptionService.java"));
        for (String marker : new String[]{"sales.risk.override", "负责人在原业务动作中批准并执行", "CONSUMED"}) {
            assertTrue(exception.contains(marker), marker);
        }

        String copper = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(copper.contains("enforceSalesOrder(salesOrderId, \"CONFIRM\""));
        assertTrue(copper.contains("enforceSalesOrder(salesOrderId, \"AMEND\""));
        assertTrue(copper.contains("enforceOutbound(outboundId, riskOverrideReason, approvalId)"));
        assertTrue(copper.indexOf("enforceOutbound(outboundId, riskOverrideReason, approvalId)")
                < copper.indexOf("consumeInventoryPackaging", copper.indexOf("public Long confirmOutbound(Long outboundId, String")));
    }

    @Test
    void controllerKeepsPreviewSeparateFromOneTimeOverrideReason() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/SalesRiskController.java"));
        for (String path : new String[]{
                "/sales-risk/partners/{partnerId}/policy", "/sales-orders/{id}/risk",
                "/sales-orders/{id}/risk-history", "/outbounds/{id}/risk",
                "/outbounds/{id}/risk-history"
        }) assertTrue(controller.contains(path), path);
        String copperController = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/CopperController.java"));
        assertTrue(copperController.contains("SalesRiskDtos.OverrideRequest"));
    }
}
