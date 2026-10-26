package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrmIndependentDomainContractTest {
    @Test
    void migrationCreatesIndependentCustomerOperatingDomainAndAuditTrail() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V23__crm_independent_domain.sql"));
        for (String table : new String[]{
                "crm_lead", "crm_customer_profile", "crm_opportunity", "crm_quote", "crm_quote_item",
                "crm_quote_conversion", "crm_activity", "crm_followup_plan", "crm_domain_event", "crm_option"
        }) assertTrue(sql.contains("CREATE TABLE " + table), table);
        for (String permission : new String[]{
                "crm.record.view", "crm.record.manage", "crm.record.view.all",
                "crm.quote.convert", "crm.analysis.view", "crm.option.manage"
        }) assertTrue(sql.contains("'" + permission + "'"), permission);
        assertTrue(sql.contains("LEGACY_FOLLOWUP"));
        assertTrue(sql.contains("partner_id bigint unsigned NOT NULL"));
        assertFalse(sql.contains("purchase_order_id"), "CRM must not own or require purchase workflow state");

        String correction = Files.readString(Path.of(
                "src/main/resources/db/migration/V24__crm_exclude_supplier_legacy_followups.sql"));
        assertTrue(correction.contains("source_type='LEGACY_FOLLOWUP'"));
        assertTrue(correction.contains("(p.type_mask & 1)=0"));
        assertTrue(correction.contains("original"));

        String ownerWorklist = Files.readString(Path.of(
                "src/main/resources/db/migration/V25__crm_owner_worklist.sql"));
        assertTrue(ownerWorklist.contains("repurchase_reminder_enabled"));
        assertTrue(ownerWorklist.contains("repurchase_cycle_days"));
        assertTrue(ownerWorklist.contains("BETWEEN 1 AND 3650"));
    }

    @Test
    void serviceKeepsActivitiesPlansQuotesAndSalesConversionSemanticallySeparate() throws Exception {
        String service = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CrmService.java"));
        for (String operation : new String[]{
                "qualifyLead", "disqualifyLead", "reactivateLead", "changeOpportunityStage",
                "activateQuote", "reviseQuote", "decideQuote", "convertQuote",
                "createActivity", "savePlan", "completePlan", "customerAnalysis"
        }) assertTrue(service.contains(operation + "("), operation);
        assertTrue(service.contains("createSalesOrderFromCrmQuote"));
        assertTrue(service.contains("source_type='FOLLOWUP_PLAN'"));
        assertTrue(service.contains("plans(\"OPEN\", null, true)"));
        assertTrue(service.contains("只能查看和维护本人负责的 CRM 记录"));
        assertTrue(service.contains("商机阶段不能无痕倒退"));
        assertTrue(service.contains("报价转销售订单还需要销售订单维护权限"));
        assertTrue(service.contains("expiringQuoteRows"));
        assertTrue(service.contains("repurchaseRows"));
        assertTrue(service.contains("receivableReminderAmount"));
        assertTrue(service.contains("latestOrderDate().isAfter(row.lastBusinessDate())"));
        assertTrue(service.contains("private List<CrmDtos.CustomerAnalysisRow> customerAnalysisRows()"));
        assertFalse(service.contains("rows.stream().map(row -> {\n            CrmDtos.CustomerAnalysis analysis = customerAnalysis"),
                "dashboard analysis must not run a query bundle per customer");

        String sales = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/CopperService.java"));
        assertTrue(sales.contains("CRM only supplies an accepted quote snapshot"));
        assertTrue(sales.contains("quotePackagingSnapshots"));
        assertTrue(sales.contains("quoteProductIds"));
        assertTrue(sales.contains(
                "req, historicalSnapshots, historicalPriceSnapshots, historicalProductIds, taxTerms"));
    }

    @Test
    void controllerSecurityAndExportCatalogCoverCrmWithoutCouplingPurchase() throws Exception {
        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/CrmController.java"));
        String security = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/security/SecurityInterceptor.java"));
        String export = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportCatalog.java"));
        for (String route : new String[]{
                "/leads", "/qualify", "/customers", "/opportunities", "/stage", "/quotes",
                "/activate", "/revise", "/accept", "/reject", "/convert-to-sales-order",
                "/activities", "/plans", "/complete"
        }) assertTrue(controller.contains(route), route);
        assertTrue(security.contains("crm.quote.convert"));
        assertTrue(security.contains("crm.analysis.view"));
        assertTrue(export.contains("starts(table, \"crm_\")"));
        assertFalse(controller.contains("purchase"));
    }
}
