package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BusinessExportCatalogTest {
    private static final Pattern MIGRATION_VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "CREATE\\s+TABLE(?:\\s+IF\\s+NOT\\s+EXISTS)?\\s+`?([a-z0-9_]+)`?",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> V8_TO_V24_TABLES = Set.of(
            "inventory_cost_policy", "inventory_transfer_order", "inventory_transfer_item",
            "inventory_stocktake_order", "inventory_stocktake_item", "purchase_return_order",
            "purchase_return_item", "inventory_cost_adjustment", "inventory_cost_adjustment_item",
            "inventory_document_event", "inventory_opening_cost_alignment",
            "business_posting_rule_version", "business_posting_batch", "business_posting_entry",
            "business_posting_event", "business_order_revision",
            "accounting_entity", "accounting_period", "accounting_account", "accounting_rule_version",
            "accounting_account_event", "accounting_voucher", "accounting_voucher_line",
            "accounting_voucher_event", "accounting_ledger_entry", "accounting_generation_issue",
            "accounting_generation_result", "accounting_period_event",
            "tax_rate_rule", "tax_invoice", "tax_invoice_line", "tax_invoice_relation",
            "tax_invoice_event", "tax_invoice_use_event",
            "financial_report_line", "financial_report_account_map", "business_bank_account",
            "bank_reconciliation_session", "bank_statement_line", "bank_reconciliation_match",
            "bank_reconciliation_difference", "bank_reconciliation_pending_system",
            "bank_reconciliation_event", "accounting_period_close_run", "accounting_period_close_check",
            "crm_option", "crm_number_sequence", "crm_lead", "crm_customer_profile",
            "crm_opportunity", "crm_quote", "crm_quote_item", "crm_quote_conversion",
            "crm_activity", "crm_followup_plan", "crm_domain_event"
    );

    @Test
    void everyApplicationTableCreatedByCurrentMigrationsHasDirectoryAndProgressPhase() throws Exception {
        Map<String, Integer> tables = createdTables();
        assertEquals(255, tables.size(), "迁移建表数变化时必须同步评审完整业务导出与恢复合同");
        assertEquals(49, tables.get("unit_lookup_token"),
                "单位全局查询令牌必须进入完整业务导出与恢复合同");
        assertEquals("master-data", BusinessExportCatalog.directoryFor("unit_lookup_token"));
        assertEquals("inventory", BusinessExportCatalog.directoryFor("inventory_non_trade_document"));
        assertEquals("inventory", BusinessExportCatalog.directoryFor("inventory_non_trade_item"));
        assertEquals("inventory", BusinessExportCatalog.directoryFor("inventory_package_instance_usage"));
        assertEquals("purchasing", BusinessExportCatalog.directoryFor("purchase_quote_option"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("sales_cost_allocation"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("outbound_delivery_event"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("external_share_link"));
        assertEquals("audit", BusinessExportCatalog.directoryFor("integration_access_key"));
        assertEquals("purchasing", BusinessExportCatalog.directoryFor("purchase_followup_event"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("external_confirmation_packet"));
        assertEquals("audit", BusinessExportCatalog.directoryFor("document_intake_batch"));
        tables.put("flyway_schema_history", 0);

        for (Map.Entry<String, Integer> entry : tables.entrySet()) {
            String table = entry.getKey();
            if (BusinessExportCatalog.EXCLUDED_TABLES.contains(table)) continue;
            String directory = BusinessExportCatalog.directoryFor(table);
            assertTrue(BusinessExportCatalog.EXPORT_DIRECTORIES.contains(directory),
                    table + "（V" + entry.getValue() + "）目录未登记");
            assertTrue(!BusinessExportCatalog.phaseFor(table).isBlank(),
                    table + "（V" + entry.getValue() + "）进度阶段未登记");
        }
    }

    @Test
    void v8ThroughV24ErpTablesAreExplicitlyCoveredByModule() throws Exception {
        Map<String, Integer> tables = createdTables();
        Set<String> actual = tables.entrySet().stream()
                .filter(entry -> entry.getValue() >= 8 && entry.getValue() <= 24)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(V8_TO_V24_TABLES, actual, "V8-V24 新表集合变化时必须同步导出与恢复验收");

        Map<String, Long> moduleCounts = actual.stream().collect(Collectors.groupingBy(
                BusinessExportCatalog::directoryFor, Collectors.counting()));
        assertEquals(Map.of(
                "inventory", 9L,
                "purchasing", 2L,
                "audit", 5L,
                "accounting", 12L,
                "tax-invoices", 6L,
                "financial-close", 11L,
                "crm", 11L
        ), moduleCounts);
    }

    @Test
    void runtimeSecretsAndAttachmentBlobsAreExplicitlyExcluded() {
        assertTrue(BusinessExportCatalog.EXCLUDED_TABLES.contains("system_user_session"));
        assertTrue(BusinessExportCatalog.EXCLUDED_TABLES.contains("inventory_atp_lock"));
        assertEquals("inventory", BusinessExportCatalog.directoryFor("inventory_atp_snapshot"));
        assertTrue(BusinessExportCatalog.excludedColumn("system_user", "password_hash"));
        assertTrue(BusinessExportCatalog.excludedColumn("inbound_attachment", "file_content"));
        assertTrue(BusinessExportCatalog.excludedColumn("inbound_review_snapshot_attachment", "file_content"));
    }

    @Test
    void businessTotalsCoverEveryV8ThroughV24ErpDomain() throws Exception {
        String writer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportArchiveWriter.java"));
        for (String metric : Set.of(
                "inventory_transfer.total_quantity.",
                "inventory_stocktake.cost_difference.",
                "purchase_return.payable_reduction.",
                "inventory_cost_adjustment.difference_amount.",
                "business_posting_entry.amount.",
                "accounting_voucher.debit.",
                "accounting_ledger.credit.total",
                "tax_invoice.total_amount.",
                "bank_match.amount.",
                "period_close.net_profit.",
                "crm_opportunity.estimated_amount.",
                "crm_quote.total_amount."
        )) {
            assertTrue(writer.contains(metric), "业务合计缺少 V8-V24 指标：" + metric);
        }
        for (String evidenceSource : Set.of(
                "business_evidence_attachment", "tax_invoice_import_batch",
                "bank_statement_import_batch", "product_import_job",
                "accounting_archive_batch")) {
            assertTrue(writer.contains("SELECT '" + evidenceSource + "'"),
                    "完整业务导出缺少新增模块原始文件：" + evidenceSource);
        }
        assertTrue(writer.contains("'PRODUCT_IMPORT', 'IMPORT_SOURCE'"));
        assertTrue(writer.contains("a.source_file_size, a.source_file_sha256, a.source_storage_path"));
        assertTrue(writer.contains(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        assertTrue(writer.contains(
                "content.length != expectedSize || !sha256(content).equalsIgnoreCase(expectedSha)"));
    }

    @Test
    void erp12ElectronicCredentialAndTaxWorkpaperTablesAreFullyExported() throws Exception {
        Map<String, Integer> tables = createdTables();
        Set<String> erp12 = tables.entrySet().stream()
                .filter(entry -> entry.getValue() == 44)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "electronic_credential", "electronic_credential_verification",
                "electronic_credential_relation", "electronic_credential_event",
                "tax_workpaper", "tax_workpaper_line", "tax_workpaper_event"
        ), erp12);
        assertTrue(erp12.stream().allMatch(table -> "tax-invoices".equals(BusinessExportCatalog.directoryFor(table))));

        String writer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportArchiveWriter.java"));
        assertTrue(writer.contains("SELECT 'electronic_credential'"));
        assertTrue(writer.contains("SELECT 'electronic_credential_verification'"));
        assertTrue(writer.contains("electronic_credential.count."));
        assertTrue(writer.contains("tax_workpaper.estimated_vat_payable."));
    }

    @Test
    void paymentAllocationFoundationIsExportedWithAuditableTotals() throws Exception {
        Map<String, Integer> tables = createdTables();
        assertEquals(32, tables.get("payment_allocation"));
        assertEquals(32, tables.get("supplier_payment_allocation"));
        assertEquals(33, tables.get("payment_allocation_event"));
        assertEquals(33, tables.get("supplier_payment_allocation_event"));
        assertEquals("cash-and-settlement", BusinessExportCatalog.directoryFor("payment_allocation"));
        assertEquals("cash-and-settlement", BusinessExportCatalog.directoryFor("supplier_payment_allocation"));

        String writer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportArchiveWriter.java"));
        assertTrue(writer.contains("payment_allocation.amount."));
        assertTrue(writer.contains("supplier_payment_allocation.amount."));
        assertTrue(writer.contains("customer_payment.unallocated_amount."));
        assertTrue(writer.contains("supplier_payment.unallocated_amount."));
        assertTrue(writer.contains("payment_allocation_event.amount."));
        assertTrue(writer.contains("supplier_payment_allocation_event.amount."));
    }

    @Test
    void salesRiskTablesBelongToSalesExport() {
        assertEquals("sales", BusinessExportCatalog.directoryFor("sales_risk_policy"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("sales_risk_policy_version"));
        assertEquals("sales", BusinessExportCatalog.directoryFor("sales_risk_snapshot"));
    }

    @Test
    void openingMigrationAuditAndReconciliationFactsAreFullyExported() throws Exception {
        Map<String, Integer> tables = createdTables();
        Set<String> erp18 = tables.entrySet().stream()
                .filter(entry -> entry.getValue() == 55)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "data_migration_batch", "data_migration_row", "data_migration_event",
                "data_migration_check_run", "data_migration_check_issue", "data_migration_fund_opening"
        ), erp18);
        assertTrue(erp18.stream().allMatch(table -> "audit".equals(BusinessExportCatalog.directoryFor(table))));

        String writer = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/BusinessExportArchiveWriter.java"));
        assertTrue(writer.contains("data_migration.total_rows."));
        assertTrue(writer.contains("data_migration.applied_rows."));
        assertTrue(writer.contains("data_migration_fund.opening_balance."));
        assertTrue(writer.contains("data_migration_check.issue_count."));
    }

    private Map<String, Integer> createdTables() throws IOException {
        Map<String, Integer> tables = new LinkedHashMap<>();
        Path migrations = Path.of("src/main/resources/db/migration");
        try (Stream<Path> paths = Files.list(migrations)) {
            for (Path path : paths.sorted().toList()) {
                Matcher versionMatcher = MIGRATION_VERSION.matcher(path.getFileName().toString());
                if (!versionMatcher.matches()) continue;
                int version = Integer.parseInt(versionMatcher.group(1));
                Matcher tableMatcher = CREATE_TABLE.matcher(Files.readString(path));
                while (tableMatcher.find()) {
                    String table = tableMatcher.group(1).toLowerCase();
                    Integer previous = tables.putIfAbsent(table, version);
                    assertTrue(previous == null, table + " 被 V" + previous + " 和 V" + version + " 重复建表");
                }
            }
        }
        return tables;
    }
}
