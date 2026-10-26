package com.soyia.copperv2.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class BusinessExportCatalog {
    static final String FORMAT_VERSION = "copper-export/v1";
    static final Set<String> EXCLUDED_TABLES = Set.of(
            "flyway_schema_history", "system_user_session", "inventory_atp_lock"
    );
    static final Set<String> EXPORT_DIRECTORIES = Set.of(
            "master-data", "purchasing", "inventory", "sales", "receivables-payables",
            "cash-and-settlement", "accounting", "tax-invoices", "financial-close", "crm", "audit"
    );
    static final Map<String, Set<String>> EXCLUDED_COLUMNS = Map.of(
            "system_user", Set.of("password_hash"),
            "inbound_attachment", Set.of("file_content"),
            "inbound_review_snapshot_attachment", Set.of("file_content")
    );

    private static final Map<String, String> ENUM_NAMES = Map.ofEntries(
            Map.entry("ENABLED", "启用"), Map.entry("DISABLED", "停用"),
            Map.entry("ACTIVE", "有效"), Map.entry("INACTIVE", "无效"),
            Map.entry("DRAFT", "草稿"), Map.entry("PENDING", "待处理"),
            Map.entry("RUNNING", "处理中"), Map.entry("COMPLETED", "已完成"),
            Map.entry("FAILED", "失败"), Map.entry("CANCELLED", "已取消"),
            Map.entry("WITHDRAWN", "已撤回"), Map.entry("EXPIRED", "已失效"),
            Map.entry("CONSUMED", "已执行"), Map.entry("INVALIDATED", "已失效"),
            Map.entry("CONFIRMED", "已确认"), Map.entry("REVERSED", "已冲正"),
            Map.entry("POSTING", "过账中"), Map.entry("POSTED", "已过账"),
            Map.entry("INCREASE", "增加"), Map.entry("DECREASE", "减少"),
            Map.entry("AMENDMENT", "订单变更"), Map.entry("INITIAL", "初始版本"),
            Map.entry("REJECTED", "已拒绝"), Map.entry("SUBMITTED", "已提交"),
            Map.entry("APPROVED", "已通过"), Map.entry("STOCKED", "已入库"),
            Map.entry("ORDERED", "已确认订单"), Map.entry("SHIPPED", "已出库"),
            Map.entry("SETTLED", "已结清"), Map.entry("UNPAID", "未付款"),
            Map.entry("PARTIAL", "部分完成"), Map.entry("PAID", "已付款"),
            Map.entry("IN_TRANSIT", "在途"), Map.entry("BATCH_ACTUAL", "批次实际成本法"),
            Map.entry("HALF_UP", "四舍五入"),
            Map.entry("RECEIVE", "增加"), Map.entry("DEDUCT", "减少"),
            Map.entry("IN", "入"), Map.entry("OUT", "出"),
            Map.entry("CUSTOMER", "客户"), Map.entry("SUPPLIER", "供应商"),
            Map.entry("RECEIVABLE", "应收"), Map.entry("PAYABLE", "应付"),
            Map.entry("ADVANCE_RECEIPT", "预收"), Map.entry("ADVANCE_PAYMENT", "预付"),
            Map.entry("BANK", "银行"), Map.entry("CASH", "现金"),
            Map.entry("ACCEPTANCE", "承兑"), Map.entry("FULL", "全部数据"),
            Map.entry("ALL", "全部"), Map.entry("SELF", "本人"),
            Map.entry("DEPARTMENT", "本部门"), Map.entry("DEPARTMENT_AND_CHILDREN", "本部门及下级"),
            Map.entry("CUSTOM", "自定义"), Map.entry("SYSTEM", "系统"),
            Map.entry("MANUAL", "人工"), Map.entry("UPLOAD", "上传"),
            Map.entry("LOCAL_FILE", "本地文件"), Map.entry("UNLIMITED", "无限期"),
            Map.entry("RESERVED", "已占用"), Map.entry("RELEASED", "已释放"),
            Map.entry("UNAPPLIED", "未分配"), Map.entry("SETTLEMENT", "普通结算"),
            Map.entry("ADVANCE", "预收预付"), Map.entry("MULTI", "多单分配"),
            Map.entry("LEGACY_SINGLE", "单据单分配"), Map.entry("REALLOCATION", "重分配"),
            Map.entry("APPLIED", "已分配"), Map.entry("HEAD_REVERSED", "资金头已冲正"),
            Map.entry("UNALLOCATED_CUSTOMER_RECEIPT", "客户普通收款暂未分配"),
            Map.entry("UNALLOCATED_SUPPLIER_PAYMENT", "供应商普通付款暂未分配"),
            Map.entry("CUSTOMER_RECEIPT_ALLOCATION", "客户收款核销重分类"),
            Map.entry("SUPPLIER_PAYMENT_ALLOCATION", "供应商付款核销重分类"),
            Map.entry("ALLOCATION_CHANGE", "核销分配调整"),
            Map.entry("PREFLIGHT_PASSED", "预检通过"), Map.entry("PREFLIGHT_FAILED", "预检失败"),
            Map.entry("CUTOVER", "已确认切换"), Map.entry("ROLLED_BACK", "已撤销"),
            Map.entry("PARTIAL_DELIVERED", "部分交付"), Map.entry("DELIVERED", "已交付"),
            Map.entry("DISPATCHED", "已发出"), Map.entry("PARTIAL_ACCEPTED", "差异实收"),
            Map.entry("ACCEPTED", "已实收")
    );

    private BusinessExportCatalog() {}

    static String directoryFor(String table) {
        if (starts(table, "acceptance_bill", "payment", "supplier_payment", "treasury_")) return "cash-and-settlement";
        if (starts(table, "receivable_", "payable_", "partner_account", "partner_fund_", "customer_advance_")) {
            return "receivables-payables";
        }
        if (starts(table, "sales_order", "sales_risk_", "sales_cost_", "direct_trade_", "outbound_", "sales_return_",
                "platform_listing", "external_share_", "external_confirmation_")) return "sales";
        if (starts(table, "inventory_", "landed_cost_", "stock_balance", "scan_log")) return "inventory";
        if (starts(table, "purchase_order", "purchase_quote_", "purchase_followup_", "purchase_return", "inbound_")) return "purchasing";
        if (starts(table, "tax_invoice", "tax_rate_rule", "electronic_credential", "tax_workpaper")) return "tax-invoices";
        if (starts(table, "financial_report_", "business_bank_account", "bank_reconciliation_", "bank_statement",
                "accounting_period_close_")) {
            return "financial-close";
        }
        if (starts(table, "accounting_")) return "accounting";
        if (starts(table, "business_evidence_", "business_document_output_log", "risk_exception_",
                "data_migration_", "document_intake_")) return "audit";
        if (starts(table, "crm_")) return "crm";
        if (starts(table, "price_")) return "master-data";
        if (starts(table, "security_", "integration_access_", "system_user", "system_upgrade_audit", "business_export_task",
                "business_posting_", "business_order_revision")) return "audit";
        if (starts(table, "organization_", "partner", "product", "packaging_", "unit", "warehouse",
                "supplier_product_", "conductor_construction")) return "master-data";
        throw new IllegalStateException("导出目录尚未覆盖业务表：" + table);
    }

    static boolean excludedColumn(String table, String column) {
        return EXCLUDED_COLUMNS.getOrDefault(table, Set.of()).contains(column);
    }

    static String phaseFor(String table) {
        return switch (directoryFor(table)) {
            case "master-data" -> "MASTER_DATA";
            case "purchasing" -> "PURCHASING";
            case "inventory" -> "INVENTORY";
            case "sales" -> "SALES";
            case "receivables-payables" -> "RECEIVABLES_PAYABLES";
            case "cash-and-settlement" -> "CASH_SETTLEMENT";
            case "accounting" -> "ACCOUNTING";
            case "tax-invoices" -> "TAX_INVOICES";
            case "financial-close" -> "FINANCIAL_CLOSE";
            case "crm" -> "CRM";
            case "audit" -> "AUDIT_DATA";
            default -> throw new IllegalStateException("未知导出阶段：" + table);
        };
    }

    static boolean enumColumn(String column) {
        String value = column.toLowerCase(Locale.ROOT);
        return value.equals("status") || value.equals("type") || value.equals("action") || value.equals("direction")
                || value.endsWith("_status") || value.endsWith("_type") || value.endsWith("_mode")
                || value.endsWith("_method") || value.endsWith("_direction") || value.endsWith("_action")
                || value.endsWith("_unit") || value.endsWith("_source") || value.equals("source");
    }

    static String enumChineseName(String value) {
        if (value == null || value.isBlank()) return "空值";
        return ENUM_NAMES.getOrDefault(value.toUpperCase(Locale.ROOT), "业务值：" + value);
    }

    private static boolean starts(String table, String... prefixes) {
        for (String prefix : prefixes) if (table.equals(prefix) || table.startsWith(prefix)) return true;
        return false;
    }
}
