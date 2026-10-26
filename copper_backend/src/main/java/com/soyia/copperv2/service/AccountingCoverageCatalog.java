package com.soyia.copperv2.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Executable accounting boundary for every business fact. A fact must be declared here before it
 * can silently enter (or intentionally stay out of) the general ledger.
 */
public final class AccountingCoverageCatalog {
    private AccountingCoverageCatalog() {}

    public enum GenerationMode {
        ACCOUNTING_RULE, SOURCE_REVERSAL, DIRECT_VOUCHER, NO_VOUCHER
    }

    public record FactPolicy(String category, String name, String sourceType, String postingAction,
                             String factType, String factDirection, GenerationMode mode,
                             String baselineDebitCode, String baselineCreditCode,
                             String resultDescription, String rationale) {
        String key() {
            return AccountingCoverageCatalog.key(sourceType, postingAction, factType, factDirection);
        }
    }

    public record Scenario(String scenarioCode, String category, String name, String sourceType,
                           String postingAction, String factType, String factDirection,
                           String settlementMethod, GenerationMode mode, boolean voucherRequired,
                           String baselineDebitCode, String baselineCreditCode,
                           String resultDescription, String evidence) {}

    private static final List<FactPolicy> FACT_POLICIES = List.of(
            noRule("采购与库存", "采购入库增加实际结存", "INBOUND", "POST", "INVENTORY_ON_HAND", "INCREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            rule("采购与库存", "采购入库确认库存成本", "INBOUND", "POST", "INVENTORY_COST", "INCREASE",
                    "1405", "224199", "借库存商品，贷采购暂估及价差"),
            rule("采购与库存", "采购入库形成应付", "INBOUND", "POST", "PAYABLE", "INCREASE",
                    "224199", "2202", "借采购暂估及价差，贷应付账款"),

            noRule("销售与成本", "销售出库减少实际结存", "OUTBOUND", "SHIP", "INVENTORY_ON_HAND", "DECREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            rule("销售与成本", "销售出库结转成本", "OUTBOUND", "SHIP", "INVENTORY_COST", "DECREASE",
                    "5401", "1405", "借主营业务成本，贷库存商品"),
            noRule("销售与成本", "销售履约进度", "OUTBOUND", "SHIP", "SALES_FULFILLMENT", "INCREASE",
                    "经营履约事实，不重复确认收入或成本"),
            rule("销售与成本", "销售结算形成应收", "OUTBOUND", "SETTLE", "RECEIVABLE", "INCREASE",
                    "1122", "5001", "借应收账款，贷主营业务收入"),

            rule("直运购销", "客户实收形成应收及收入", "DIRECT_TRADE", "DELIVERY_POST", "RECEIVABLE", "INCREASE",
                    "1122", "5001", "借应收账款，贷主营业务收入；不经过自有库存"),
            rule("直运购销", "客户实收形成成本及应付", "DIRECT_TRADE", "DELIVERY_POST", "PAYABLE", "INCREASE",
                    "5401", "2202", "借主营业务成本，贷应付账款；不生成库存事实"),
            rule("直运退货", "退货冲减应收及收入", "DIRECT_TRADE", "RETURN_POST", "RECEIVABLE", "DECREASE",
                    "5001", "1122", "借主营业务收入，贷应收账款"),
            rule("直运退货", "退货冲减成本及应付", "DIRECT_TRADE", "RETURN_POST", "PAYABLE", "DECREASE",
                    "2202", "5401", "借应付账款，贷主营业务成本"),
            rule("直运退货", "退货冲正恢复应收及收入", "DIRECT_TRADE", "RETURN_REVERSE", "RECEIVABLE", "INCREASE",
                    "1122", "5001", "借应收账款，贷主营业务收入"),
            rule("直运退货", "退货冲正恢复成本及应付", "DIRECT_TRADE", "RETURN_REVERSE", "PAYABLE", "INCREASE",
                    "5401", "2202", "借主营业务成本，贷应付账款"),

            noRule("销售退货", "销售退货恢复实际结存", "SALES_RETURN", "POST", "INVENTORY_ON_HAND", "INCREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            rule("销售退货", "销售退货恢复库存成本", "SALES_RETURN", "POST", "INVENTORY_COST", "INCREASE",
                    "1405", "5401", "借库存商品，贷主营业务成本"),
            rule("销售退货", "销售退货冲减应收", "SALES_RETURN", "POST", "RECEIVABLE", "DECREASE",
                    "5001", "1122", "借主营业务收入，贷应收账款"),

            noRule("采购退货", "采购退货减少实际结存", "PURCHASE_RETURN", "POST", "INVENTORY_ON_HAND", "DECREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            rule("采购退货", "采购退货冲减库存成本", "PURCHASE_RETURN", "POST", "INVENTORY_COST", "DECREASE",
                    "224199", "1405", "借采购暂估及价差，贷库存商品"),
            rule("采购退货", "采购退货冲减应付", "PURCHASE_RETURN", "POST", "PAYABLE", "DECREASE",
                    "2202", "224199", "借应付账款，贷采购暂估及价差"),
            rule("采购退货", "采购退货形成供应商预付", "PURCHASE_RETURN", "POST", "SUPPLIER_PREPAY", "INCREASE",
                    "1123", "224199", "借预付账款，贷采购暂估及价差"),

            rule("调拨在途", "调拨发出转为在途", "INVENTORY_TRANSFER", "DISPATCH", "INVENTORY_ON_HAND", "DECREASE",
                    "1407", "1405", "借在途物资，贷库存商品"),
            noRule("调拨在途", "调拨发出形成在途事实", "INVENTORY_TRANSFER", "DISPATCH", "INVENTORY_IN_TRANSIT", "INCREASE",
                    "与实际结存事实共同描述同一次价值转移，避免重复制证"),
            noRule("调拨在途", "调拨收货冲减在途事实", "INVENTORY_TRANSFER", "RECEIVE", "INVENTORY_IN_TRANSIT", "DECREASE",
                    "与实际结存事实共同描述同一次价值转移，避免重复制证"),
            rule("调拨在途", "调拨收货转入库存", "INVENTORY_TRANSFER", "RECEIVE", "INVENTORY_ON_HAND", "INCREASE",
                    "1405", "1407", "借库存商品，贷在途物资"),

            noRule("盘点", "盘盈增加实际结存", "INVENTORY_STOCKTAKE", "POST", "INVENTORY_ON_HAND", "INCREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            noRule("盘点", "盘亏减少实际结存", "INVENTORY_STOCKTAKE", "POST", "INVENTORY_ON_HAND", "DECREASE",
                    "数量事实；金额由库存成本事实单独制证"),
            rule("盘点", "盘盈增加库存成本", "INVENTORY_STOCKTAKE", "POST", "INVENTORY_COST", "INCREASE",
                    "1405", "1901", "借库存商品，贷待处理财产损溢"),
            rule("盘点", "盘亏减少库存成本", "INVENTORY_STOCKTAKE", "POST", "INVENTORY_COST", "DECREASE",
                    "1901", "1405", "借待处理财产损溢，贷库存商品"),

            rule("成本调整", "库存成本调增", "INVENTORY_COST_ADJUSTMENT", "POST", "INVENTORY_COST", "INCREASE",
                    "1405", "1901", "借库存商品，贷待处理财产损溢"),
            rule("成本调整", "库存成本调减", "INVENTORY_COST_ADJUSTMENT", "POST", "INVENTORY_COST", "DECREASE",
                    "1901", "1405", "借待处理财产损溢，贷库存商品"),

            noRule("其他出入库", "其他入库增加实际结存", "INVENTORY_NON_TRADE", "POST", "INVENTORY_ON_HAND", "INCREASE",
                    "数量事实；金额由对应库存成本事实单独制证"),
            noRule("其他出入库", "送样、领用、损耗或其他出库减少实际结存", "INVENTORY_NON_TRADE", "POST", "INVENTORY_ON_HAND", "DECREASE",
                    "数量事实；金额由对应库存成本事实单独制证"),
            rule("其他出入库", "送样结转销售费用", "INVENTORY_NON_TRADE", "POST", "NON_TRADE_SAMPLE", "DECREASE",
                    "5601", "1405", "借销售费用，贷库存商品"),
            rule("其他出入库", "内部领用结转管理费用", "INVENTORY_NON_TRADE", "POST", "NON_TRADE_INTERNAL_USE", "DECREASE",
                    "5602", "1405", "借管理费用，贷库存商品"),
            rule("其他出入库", "正常损耗转待处理财产损溢", "INVENTORY_NON_TRADE", "POST", "NON_TRADE_NORMAL_LOSS", "DECREASE",
                    "1901", "1405", "借待处理财产损溢，贷库存商品"),
            rule("其他出入库", "其他出库转待处理财产损溢", "INVENTORY_NON_TRADE", "POST", "NON_TRADE_OTHER_OUT", "DECREASE",
                    "1901", "1405", "借待处理财产损溢，贷库存商品"),
            rule("其他出入库", "其他入库转待处理财产损溢", "INVENTORY_NON_TRADE", "POST", "NON_TRADE_OTHER_IN", "INCREASE",
                    "1405", "1901", "借库存商品，贷待处理财产损溢"),

            rule("到岸成本", "采购附加费用计入当前结存", "LANDED_COST_ALLOCATION", "POST", "LANDED_COST_CAPITALIZED", "INCREASE",
                    "1405", "5401", "借库存商品，贷主营业务成本"),
            rule("到岸成本", "采购附加费用退出当前结存", "LANDED_COST_ALLOCATION", "ROLLBACK", "LANDED_COST_CAPITALIZED", "DECREASE",
                    "5401", "1405", "借主营业务成本，贷库存商品"),
            rule("采购退货", "退货库存中的附加费用转回期间成本", "PURCHASE_RETURN", "POST", "LANDED_COST_RETURN_EXPENSE", "INCREASE",
                    "5401", "1405", "借主营业务成本，贷库存商品"),

            rule("收款", "客户收款核销应收", "PAYMENT", "POST", "RECEIVABLE_SETTLEMENT", "DECREASE",
                    "1002", "1122", "借现金或指定银行存款，贷应收账款"),
            rule("收款", "客户普通收款暂未分配", "PAYMENT", "POST", "UNALLOCATED_CUSTOMER_RECEIPT", "INCREASE",
                    "1002", "2203", "借现金或指定银行存款，贷未分配收款清算（总账预收科目，不进入业务预收余额池）"),
            rule("收款核销", "客户收款增加应收核销", "PAYMENT", "ALLOCATION_CHANGE", "CUSTOMER_RECEIPT_ALLOCATION", "INCREASE",
                    "2203", "1122", "借未分配收款清算，贷应收账款"),
            rule("收款核销", "客户收款释放应收核销", "PAYMENT", "ALLOCATION_CHANGE", "CUSTOMER_RECEIPT_ALLOCATION", "DECREASE",
                    "1122", "2203", "借应收账款，贷未分配收款清算"),
            rule("承兑", "收到承兑票据核销应收", "PAYMENT", "POST", "RECEIVABLE_ACCEPTANCE", "DECREASE",
                    "1121", "1122", "借应收票据，贷应收账款"),
            rule("收款", "客户无单预收", "PAYMENT", "POST", "CUSTOMER_ADVANCE", "INCREASE",
                    "1002", "2203", "借现金或指定银行存款，贷预收账款"),
            rule("余额核销", "客户预收余额核销应收", "PAYMENT", "POST", "CUSTOMER_ADVANCE_SETTLEMENT", "DECREASE",
                    "2203", "1122", "借预收账款，贷应收账款；不形成现金或银行流水"),
            rule("余额核销", "客户奖励余额核销应收", "PAYMENT", "POST", "CUSTOMER_REWARD_SETTLEMENT", "DECREASE",
                    "2203", "1122", "借客户奖励义务，贷应收账款；不形成现金或银行流水"),
            rule("付款", "供应商付款核销应付", "SUPPLIER_PAYMENT", "POST", "PAYABLE_SETTLEMENT", "DECREASE",
                    "2202", "1002", "借应付账款，贷现金或指定银行存款"),
            rule("付款", "供应商普通付款暂未分配", "SUPPLIER_PAYMENT", "POST", "UNALLOCATED_SUPPLIER_PAYMENT", "INCREASE",
                    "1123", "1002", "借未分配付款清算（总账预付科目，不进入业务预付余额池），贷现金或指定银行存款"),
            rule("付款核销", "供应商付款增加应付核销", "SUPPLIER_PAYMENT", "ALLOCATION_CHANGE", "SUPPLIER_PAYMENT_ALLOCATION", "INCREASE",
                    "2202", "1123", "借应付账款，贷未分配付款清算"),
            rule("付款核销", "供应商付款释放应付核销", "SUPPLIER_PAYMENT", "ALLOCATION_CHANGE", "SUPPLIER_PAYMENT_ALLOCATION", "DECREASE",
                    "1123", "2202", "借未分配付款清算，贷应付账款"),
            rule("付款", "供应商无单预付", "SUPPLIER_PAYMENT", "POST", "SUPPLIER_PREPAY", "INCREASE",
                    "1123", "1002", "借预付账款，贷现金或指定银行存款"),
            rule("余额核销", "供应商预付余额核销应付", "SUPPLIER_PAYMENT", "POST", "SUPPLIER_PREPAY_SETTLEMENT", "DECREASE",
                    "2202", "1123", "借应付账款，贷预付账款；不形成现金或银行流水"),
            rule("余额核销", "供应商返利余额核销应付", "SUPPLIER_PAYMENT", "POST", "SUPPLIER_REWARD_SETTLEMENT", "DECREASE",
                    "2202", "1123", "借应付账款，贷供应商返利资产；不形成现金或银行流水"),
            rule("退款", "客户预收款退款", "CUSTOMER_ADVANCE_REFUND", "POST", "CUSTOMER_ADVANCE_REFUND", "DECREASE",
                    "2203", "1002", "借预收账款，贷现金或指定银行存款"),
            rule("往来余额调整", "客户预收期初", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_ADVANCE_OPENING", "INCREASE",
                    "3104", "2203", "借未分配利润，贷客户预收；仅用于空白账户期初"),
            rule("往来余额调整", "供应商预付期初", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_PREPAY_OPENING", "INCREASE",
                    "1123", "3104", "借供应商预付，贷未分配利润；仅用于空白账户期初"),
            rule("往来余额调整", "客户奖励确认", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_REWARD_GRANT", "INCREASE",
                    "5601", "2203", "借销售费用，贷客户奖励义务；税票单独处理"),
            rule("往来余额调整", "供应商返利确认", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_REBATE_GRANT", "INCREASE",
                    "1123", "5051", "借供应商返利资产，贷其他业务收入；税票单独处理"),
            rule("往来余额调整", "客户预收差错调增", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_ADVANCE_CORRECTION", "INCREASE",
                    "5711", "2203", "借营业外支出，贷客户预收；草稿凭证应由会计复核"),
            rule("往来余额调整", "客户预收差错调减", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_ADVANCE_CORRECTION", "DECREASE",
                    "2203", "5051", "借客户预收，贷其他业务收入；草稿凭证应由会计复核"),
            rule("往来余额调整", "客户奖励差错调增", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_REWARD_CORRECTION", "INCREASE",
                    "5711", "2203", "借营业外支出，贷客户奖励义务；草稿凭证应由会计复核"),
            rule("往来余额调整", "客户奖励差错调减", "PARTNER_FUND_ADJUSTMENT", "POST", "CUSTOMER_REWARD_CORRECTION", "DECREASE",
                    "2203", "5051", "借客户奖励义务，贷其他业务收入；草稿凭证应由会计复核"),
            rule("往来余额调整", "供应商预付差错调增", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_PREPAY_CORRECTION", "INCREASE",
                    "1123", "5051", "借供应商预付，贷其他业务收入；草稿凭证应由会计复核"),
            rule("往来余额调整", "供应商预付差错调减", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_PREPAY_CORRECTION", "DECREASE",
                    "5711", "1123", "借营业外支出，贷供应商预付；草稿凭证应由会计复核"),
            rule("往来余额调整", "供应商返利差错调增", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_REWARD_CORRECTION", "INCREASE",
                    "1123", "5051", "借供应商返利资产，贷其他业务收入；草稿凭证应由会计复核"),
            rule("往来余额调整", "供应商返利差错调减", "PARTNER_FUND_ADJUSTMENT", "POST", "SUPPLIER_REWARD_CORRECTION", "DECREASE",
                    "5711", "1123", "借营业外支出，贷供应商返利资产；草稿凭证应由会计复核"),
            rule("往来调整", "应收调整", "RECEIVABLE_ADJUSTMENT", "POST", "RECEIVABLE_ADJUSTMENT", "DECREASE",
                    "5711", "1122", "借营业外支出，贷应收账款"),

            rule("承兑", "承兑到期或贴现实收", "ACCEPTANCE_EVENT", "POST", "ACCEPTANCE_CASH_RECEIPT", "INCREASE",
                    "1002", "1121", "借指定银行存款，贷应收票据"),
            rule("承兑", "承兑贴现费用", "ACCEPTANCE_EVENT", "POST", "ACCEPTANCE_DISCOUNT_FEE", "INCREASE",
                    "5603", "1121", "借财务费用，贷应收票据"),
            rule("承兑", "已贴现承兑退票追索", "ACCEPTANCE_EVENT", "POST", "ACCEPTANCE_BOUNCE_CASH", "DECREASE",
                    "1122", "1002", "借应收账款，贷指定银行存款"),

            noRule("采购承诺", "采购数量承诺", "PURCHASE_ORDER", "COMMITMENT_POST", "PURCHASE_COMMITMENT_QTY", "INCREASE",
                    "承诺仅用于经营控制，不进入总账"),
            noRule("采购承诺", "采购金额承诺", "PURCHASE_ORDER", "COMMITMENT_POST", "PURCHASE_COMMITMENT_AMOUNT", "INCREASE",
                    "承诺仅用于经营控制，不进入总账"),
            noRule("采购承诺", "采购数量承诺冲回", "PURCHASE_ORDER", "COMMITMENT_REVERSE", "PURCHASE_COMMITMENT_QTY", "DECREASE",
                    "承诺冲回不进入总账"),
            noRule("采购承诺", "采购金额承诺冲回", "PURCHASE_ORDER", "COMMITMENT_REVERSE", "PURCHASE_COMMITMENT_AMOUNT", "DECREASE",
                    "承诺冲回不进入总账"),
            noRule("销售承诺", "销售数量承诺", "SALES_ORDER", "COMMITMENT_POST", "SALES_COMMITMENT_QTY", "INCREASE",
                    "承诺仅用于经营控制，不进入总账"),
            noRule("销售承诺", "销售金额承诺", "SALES_ORDER", "COMMITMENT_POST", "SALES_COMMITMENT_AMOUNT", "INCREASE",
                    "承诺仅用于经营控制，不进入总账"),
            noRule("销售承诺", "销售数量承诺冲回", "SALES_ORDER", "COMMITMENT_REVERSE", "SALES_COMMITMENT_QTY", "DECREASE",
                    "承诺冲回不进入总账"),
            noRule("销售承诺", "销售金额承诺冲回", "SALES_ORDER", "COMMITMENT_REVERSE", "SALES_COMMITMENT_AMOUNT", "DECREASE",
                    "承诺冲回不进入总账")
    );

    private static final List<String> REVERSAL_SOURCES = List.of(
            "INBOUND", "OUTBOUND", "SALES_RETURN", "PURCHASE_RETURN", "INVENTORY_TRANSFER",
            "INVENTORY_STOCKTAKE", "INVENTORY_COST_ADJUSTMENT", "INVENTORY_NON_TRADE",
            "PAYMENT", "SUPPLIER_PAYMENT",
            "RECEIVABLE_ADJUSTMENT", "ACCEPTANCE_EVENT", "CUSTOMER_ADVANCE_REFUND",
            "PARTNER_FUND_ADJUSTMENT"
    );

    public static List<FactPolicy> factPolicies() {
        return FACT_POLICIES;
    }

    public static Optional<FactPolicy> policyFor(String sourceType, String postingAction,
                                                  String factType, String factDirection) {
        return FACT_POLICIES.stream().filter(policy -> policy.key().equals(
                key(sourceType, postingAction, factType, factDirection))).findFirst();
    }

    public static boolean knowsDeclaredFact(String sourceType, String postingAction, String factType) {
        if ("REVERSE".equals(postingAction) && REVERSAL_SOURCES.contains(sourceType)) return true;
        return FACT_POLICIES.stream().anyMatch(policy -> policy.sourceType().equals(sourceType)
                && policy.postingAction().equals(postingAction) && policy.factType().equals(factType));
    }

    public static List<Scenario> scenarios() {
        List<Scenario> rows = new ArrayList<>();
        for (FactPolicy policy : FACT_POLICIES) {
            if (isCashBankPolicy(policy)) {
                rows.add(scenario(policy, "CASH", policy.name() + "（现金）"));
                rows.add(scenario(policy, "BANK", policy.name() + "（银行）"));
            } else if ("RECEIVABLE_ACCEPTANCE".equals(policy.factType())) {
                rows.add(scenario(policy, "ACCEPTANCE", policy.name()));
            } else if ("ACCEPTANCE_EVENT".equals(policy.sourceType())) {
                rows.add(scenario(policy, "BANK", policy.name()));
            } else {
                rows.add(scenario(policy, "", policy.name()));
            }
        }
        for (String source : REVERSAL_SOURCES) {
            rows.add(new Scenario("REVERSAL_" + source, "冲正", source + " 来源冲正", source,
                    "REVERSE", "原凭证全部分录", "REVERSE", "", GenerationMode.SOURCE_REVERSAL,
                    true, "原借方", "原贷方", "未记账原凭证作废；已记账原凭证借贷反向冲销",
                    "复用原凭证快照，不重新匹配当前会计规则"));
        }
        rows.addAll(treasuryScenarios());
        rows.addAll(taxScenarios());
        return List.copyOf(rows);
    }

    private static boolean isCashBankPolicy(FactPolicy policy) {
        return switch (policy.sourceType()) {
            case "PAYMENT" -> "POST".equals(policy.postingAction()) && Set.of(
                    "RECEIVABLE_SETTLEMENT", "UNALLOCATED_CUSTOMER_RECEIPT", "CUSTOMER_ADVANCE"
            ).contains(policy.factType());
            case "SUPPLIER_PAYMENT" -> "POST".equals(policy.postingAction()) && Set.of(
                    "PAYABLE_SETTLEMENT", "UNALLOCATED_SUPPLIER_PAYMENT", "SUPPLIER_PREPAY"
            ).contains(policy.factType());
            case "CUSTOMER_ADVANCE_REFUND" -> true;
            default -> false;
        };
    }

    private static Scenario scenario(FactPolicy policy, String settlementMethod, String name) {
        String debit = displayAccount(policy.baselineDebitCode(), settlementMethod);
        String credit = displayAccount(policy.baselineCreditCode(), settlementMethod);
        String result = policy.resultDescription();
        String evidence = policy.rationale();
        if ("CASH".equals(settlementMethod)) {
            result = result.replace("现金或指定银行存款", "库存现金");
            evidence += "；制证时将规则占位科目 1002 替换为 1001 库存现金";
        } else if ("BANK".equals(settlementMethod) && ("1002".equals(policy.baselineDebitCode())
                || "1002".equals(policy.baselineCreditCode()))) {
            result = result.replace("现金或指定银行存款", "所选企业银行账户").replace("指定银行存款", "所选企业银行账户");
            evidence += "；制证时将规则占位科目 1002 替换为所选企业银行账户关联的末级科目";
        }
        return new Scenario(policy.sourceType() + "_" + policy.postingAction() + "_" + policy.factType()
                + "_" + policy.factDirection() + (settlementMethod.isEmpty() ? "" : "_" + settlementMethod),
                policy.category(), name, policy.sourceType(), policy.postingAction(), policy.factType(),
                policy.factDirection(), settlementMethod, policy.mode(),
                policy.mode() != GenerationMode.NO_VOUCHER, debit, credit, result, evidence);
    }

    private static String displayAccount(String code, String settlementMethod) {
        if (!"1002".equals(code)) return code;
        if ("CASH".equals(settlementMethod)) return "1001";
        if ("BANK".equals(settlementMethod)) return "所选银行科目";
        return code;
    }

    private static List<Scenario> taxScenarios() {
        return List.of(
                direct("TAX_OUTPUT_BLUE", "销项蓝票", "OUTPUT_BLUE", "5001", "22210102",
                        "借主营业务收入，贷应交销项税额"),
                direct("TAX_OUTPUT_RED", "销项红票", "OUTPUT_RED", "22210102", "5001",
                        "借应交销项税额，贷主营业务收入"),
                direct("TAX_INPUT_BLUE", "进项蓝票", "INPUT_BLUE", "22210103", "1405",
                        "借待认证进项税额，贷库存商品"),
                direct("TAX_INPUT_RED_UNUSED", "未处置进项蓝票对应红票", "INPUT_RED", "1405", "22210103",
                        "借库存商品，贷待认证进项税额"),
                direct("TAX_INPUT_RED_DEDUCTED", "已抵扣进项蓝票对应红票", "INPUT_RED", "1405", "22210101",
                        "借库存商品，贷进项税额"),
                direct("TAX_INPUT_DEDUCTION", "进项抵扣", "INPUT_DEDUCTION", "22210101", "22210103",
                        "借进项税额，贷待认证进项税额"),
                direct("TAX_INPUT_NON_DEDUCTION", "进项不抵扣", "INPUT_NON_DEDUCTION", "1405", "22210103",
                        "借库存商品，贷待认证进项税额"),
                direct("TAX_INPUT_TRANSFER_OUT", "进项税额转出", "INPUT_TRANSFER_OUT", "1405", "22210101",
                        "借库存商品，贷进项税额"),
                noTaxVoucher("TAX_INPUT_RED_NON_DEDUCTION", "不抵扣进项蓝票对应红票", "INPUT_RED_NON_DEDUCTION",
                        "复用蓝票不抵扣处置凭证，不重复生成红票凭证"),
                noTaxVoucher("TAX_INPUT_RED_TRANSFERRED_OUT", "已转出进项蓝票对应红票", "INPUT_RED_TRANSFERRED_OUT",
                        "复用蓝票进项转出处置凭证，不重复生成红票凭证"),
                new Scenario("TAX_VOID", "发票与税务", "发票作废/红票处置冲销", "TAX_INVOICE",
                        "VOID", "TAX_VOUCHER", "REVERSE", "", GenerationMode.SOURCE_REVERSAL, true,
                        "原借方", "原贷方", "未记账税票凭证作废；已记账凭证借贷反向冲销",
                        "保留原始税票凭证关系与冲销链")
        );
    }

    private static List<Scenario> treasuryScenarios() {
        return List.of(
                new Scenario("TREASURY_EXPENSE_ACCRUAL", "费用与出纳", "费用及其他应付确认",
                        "TREASURY_DOCUMENT", "CONFIRM", "EXPENSE_OR_PAYABLE_ACCRUAL", "INCREASE", "",
                        GenerationMode.DIRECT_VOUCHER, true, "所选费用/成本科目及待认证进项税", "2241",
                        "借费用或成本及待认证进项税，贷其他应付款",
                        "源单冻结费用科目与价税快照；税票另行勾稽"),
                new Scenario("TREASURY_INCOME_ACCRUAL", "费用与出纳", "其他收入及其他应收确认",
                        "TREASURY_DOCUMENT", "CONFIRM", "INCOME_OR_RECEIVABLE_ACCRUAL", "INCREASE", "",
                        GenerationMode.DIRECT_VOUCHER, true, "1231", "所选收入科目及销项税",
                        "借其他应收款，贷收入及应交销项税",
                        "源单冻结收入科目与价税快照；税票另行勾稽"),
                new Scenario("TREASURY_DOCUMENT_SETTLEMENT", "费用与出纳", "费用或其他往来实际收付",
                        "TREASURY_FUND", "SETTLE", "TREASURY_SETTLEMENT", "DECREASE", "CASH/BANK",
                        GenerationMode.DIRECT_VOUCHER, true, "1231/2241/现金或银行", "1231/2241/现金或银行",
                        "往来结清与实际资金同额制证，不以付款申请替代资金",
                        "每笔资金生成独立出纳日记账并可逐笔银行对账"),
                new Scenario("TREASURY_INTERNAL_TRANSFER", "费用与出纳", "内部账户转账",
                        "TREASURY_FUND", "TRANSFER", "INTERNAL_TRANSFER", "TRANSFER", "BANK",
                        GenerationMode.DIRECT_VOUCHER, true, "转入银行科目", "转出银行科目",
                        "借转入账户，贷转出账户；不计入经营现金流量",
                        "同一资金事项生成转出、转入两条可分别对账的日记账"),
                new Scenario("TREASURY_REVERSAL", "费用与出纳", "费用源单或资金冲正",
                        "TREASURY_DOCUMENT/TREASURY_FUND", "REVERSE", "原凭证全部分录", "REVERSE", "",
                        GenerationMode.SOURCE_REVERSAL, true, "原借方", "原贷方",
                        "未记账凭证作废；已记账凭证借贷反向冲销",
                        "先解除银行对账，再冲正资金；原单和原日记账始终保留")
        );
    }

    private static Scenario direct(String code, String name, String factType, String debit,
                                   String credit, String result) {
        return new Scenario(code, "发票与税务", name, "TAX_INVOICE", "CONFIRM", factType,
                "INCREASE", "", GenerationMode.DIRECT_VOUCHER, true, debit, credit, result,
                "TaxInvoiceService 固定税务处理器生成来源凭证");
    }

    private static Scenario noTaxVoucher(String code, String name, String factType, String result) {
        return new Scenario(code, "发票与税务", name, "TAX_INVOICE", "CONFIRM", factType,
                "REVERSE", "", GenerationMode.NO_VOUCHER, false, "", "", result,
                "红票与原蓝票处置凭证建立会计关系，避免对同一税额重复制证");
    }

    private static FactPolicy rule(String category, String name, String sourceType, String action,
                                   String factType, String direction, String debit, String credit,
                                   String result) {
        return new FactPolicy(category, name, sourceType, action, factType, direction,
                GenerationMode.ACCOUNTING_RULE, debit, credit, result,
                "必须恰好命中一条业务日期有效的已发布会计规则");
    }

    private static FactPolicy noRule(String category, String name, String sourceType, String action,
                                     String factType, String direction, String rationale) {
        return new FactPolicy(category, name, sourceType, action, factType, direction,
                GenerationMode.NO_VOUCHER, "", "", "不单独生成会计分录", rationale);
    }

    private static String key(String sourceType, String postingAction, String factType, String factDirection) {
        return String.join("|", sourceType, postingAction, factType, factDirection);
    }
}
