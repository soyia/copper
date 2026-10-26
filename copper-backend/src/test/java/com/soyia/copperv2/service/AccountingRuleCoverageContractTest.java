package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountingRuleCoverageContractTest {
    @Test
    void everyPostedFactHasExactlyOneExplicitAccountingDisposition() {
        List<AccountingCoverageCatalog.FactPolicy> policies = AccountingCoverageCatalog.factPolicies();
        Map<String, Long> counts = policies.stream().collect(Collectors.groupingBy(
                AccountingCoverageCatalog.FactPolicy::key, Collectors.counting()));
        assertTrue(counts.values().stream().allMatch(count -> count == 1),
                "同一业务事实只能有一条会计处置策略");
        assertTrue(policies.stream().allMatch(policy -> !policy.resultDescription().isBlank()
                && !policy.rationale().isBlank()), "每种事实必须说明凭证结果或不制证原因");

        Set<String> factTypes = policies.stream().map(AccountingCoverageCatalog.FactPolicy::factType)
                .collect(Collectors.toSet());
        for (String fact : List.of(
                "INVENTORY_ON_HAND", "INVENTORY_COST", "INVENTORY_IN_TRANSIT", "PAYABLE",
                "RECEIVABLE", "SALES_FULFILLMENT", "SUPPLIER_PREPAY", "CUSTOMER_ADVANCE",
                "RECEIVABLE_SETTLEMENT", "PAYABLE_SETTLEMENT", "RECEIVABLE_ACCEPTANCE",
                "UNALLOCATED_CUSTOMER_RECEIPT", "UNALLOCATED_SUPPLIER_PAYMENT",
                "CUSTOMER_RECEIPT_ALLOCATION", "SUPPLIER_PAYMENT_ALLOCATION",
                "CUSTOMER_ADVANCE_REFUND", "RECEIVABLE_ADJUSTMENT", "ACCEPTANCE_CASH_RECEIPT",
                "ACCEPTANCE_DISCOUNT_FEE", "ACCEPTANCE_BOUNCE_CASH", "PURCHASE_COMMITMENT_QTY",
                "PURCHASE_COMMITMENT_AMOUNT", "SALES_COMMITMENT_QTY", "SALES_COMMITMENT_AMOUNT"
        )) assertTrue(factTypes.contains(fact), fact);
    }

    @Test
    void edgeScenariosExposeCashBankAcceptanceReturnsTransitCostAndTaxResults() {
        List<AccountingCoverageCatalog.Scenario> scenarios = AccountingCoverageCatalog.scenarios();
        Set<String> codes = scenarios.stream().map(AccountingCoverageCatalog.Scenario::scenarioCode)
                .collect(Collectors.toSet());
        for (String required : List.of(
                "PAYMENT_POST_RECEIVABLE_SETTLEMENT_DECREASE_CASH",
                "PAYMENT_POST_RECEIVABLE_SETTLEMENT_DECREASE_BANK",
                "PAYMENT_POST_UNALLOCATED_CUSTOMER_RECEIPT_INCREASE_BANK",
                "PAYMENT_ALLOCATION_CHANGE_CUSTOMER_RECEIPT_ALLOCATION_INCREASE",
                "SUPPLIER_PAYMENT_ALLOCATION_CHANGE_SUPPLIER_PAYMENT_ALLOCATION_INCREASE",
                "PAYMENT_POST_RECEIVABLE_ACCEPTANCE_DECREASE_ACCEPTANCE",
                "CUSTOMER_ADVANCE_REFUND_POST_CUSTOMER_ADVANCE_REFUND_DECREASE_CASH",
                "CUSTOMER_ADVANCE_REFUND_POST_CUSTOMER_ADVANCE_REFUND_DECREASE_BANK",
                "PURCHASE_RETURN_POST_INVENTORY_COST_DECREASE",
                "SALES_RETURN_POST_INVENTORY_COST_INCREASE",
                "INVENTORY_TRANSFER_DISPATCH_INVENTORY_IN_TRANSIT_INCREASE",
                "INVENTORY_TRANSFER_RECEIVE_INVENTORY_IN_TRANSIT_DECREASE",
                "INVENTORY_COST_ADJUSTMENT_POST_INVENTORY_COST_INCREASE",
                "INVENTORY_COST_ADJUSTMENT_POST_INVENTORY_COST_DECREASE",
                "TAX_OUTPUT_BLUE", "TAX_OUTPUT_RED", "TAX_INPUT_BLUE",
                "TAX_INPUT_RED_UNUSED", "TAX_INPUT_RED_DEDUCTED",
                "TAX_INPUT_DEDUCTION", "TAX_INPUT_NON_DEDUCTION", "TAX_INPUT_TRANSFER_OUT",
                "TAX_INPUT_RED_NON_DEDUCTION", "TAX_INPUT_RED_TRANSFERRED_OUT", "TAX_VOID"
        )) assertTrue(codes.contains(required), required);

        Map<String, AccountingCoverageCatalog.Scenario> byCode = scenarios.stream().collect(
                Collectors.toMap(AccountingCoverageCatalog.Scenario::scenarioCode, Function.identity()));
        assertEquals("5001", byCode.get("TAX_OUTPUT_BLUE").baselineDebitCode());
        assertEquals("22210102", byCode.get("TAX_OUTPUT_RED").baselineDebitCode());
        assertEquals("1405", byCode.get("TAX_INPUT_TRANSFER_OUT").baselineDebitCode());
        assertEquals(AccountingCoverageCatalog.GenerationMode.NO_VOUCHER,
                byCode.get("INVENTORY_TRANSFER_DISPATCH_INVENTORY_IN_TRANSIT_INCREASE").mode());
        assertFalse(byCode.get("INVENTORY_TRANSFER_DISPATCH_INVENTORY_IN_TRANSIT_INCREASE").voucherRequired());
        assertEquals("1001", byCode.get("PAYMENT_POST_RECEIVABLE_SETTLEMENT_DECREASE_CASH").baselineDebitCode());
        assertEquals("所选银行科目", byCode.get("PAYMENT_POST_RECEIVABLE_SETTLEMENT_DECREASE_BANK").baselineDebitCode());
        assertFalse(byCode.get("TAX_INPUT_RED_TRANSFERRED_OUT").voucherRequired());
    }

    @Test
    void runtimeGateRunsBeforeRuleJoinAndRejectsPartialOrAmbiguousCoverage() throws Exception {
        String accounting = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingService.java"));
        int gate = accounting.indexOf("coverageService.validateBusinessBatch");
        int mapping = accounting.indexOf("mappedFacts(entityId, batch)");
        assertTrue(gate > 0 && mapping > gate, "覆盖门禁必须先于会计规则 join");
        assertTrue(accounting.contains("facts.size() != expectedMappedFacts"));

        String coverage = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/AccountingCoverageService.java"));
        assertTrue(coverage.contains("count == 0"));
        assertTrue(coverage.contains("count != 1"));
        assertTrue(coverage.contains("UNREGISTERED_RULE"));
        assertTrue(coverage.contains("UNREGISTERED_FACT"));
        assertTrue(coverage.contains("json_table"));
    }

    @Test
    void runtimeGateRejectsMissingDuplicateAndUnknownFactsWithoutCreatingPartialVoucher() {
        List<AccountingCoverageService.BatchFact> inbound = List.of(
                new AccountingCoverageService.BatchFact("INVENTORY_ON_HAND", "INCREASE", BigDecimal.ZERO),
                new AccountingCoverageService.BatchFact("INVENTORY_COST", "INCREASE", new BigDecimal("100.00")),
                new AccountingCoverageService.BatchFact("PAYABLE", "INCREASE", new BigDecimal("100.00")));

        BusinessException missing = assertThrows(BusinessException.class, () ->
                AccountingCoverageService.validateFacts(inbound, "INBOUND", "POST",
                        policy -> "PAYABLE".equals(policy.factType()) ? 0 : 1));
        assertTrue(missing.getMessage().contains("会计规则缺失"));

        BusinessException duplicate = assertThrows(BusinessException.class, () ->
                AccountingCoverageService.validateFacts(inbound, "INBOUND", "POST",
                        policy -> "PAYABLE".equals(policy.factType()) ? 2 : 1));
        assertTrue(duplicate.getMessage().contains("会计规则不唯一"));

        BusinessException unknown = assertThrows(BusinessException.class, () ->
                AccountingCoverageService.validateFacts(List.of(
                                new AccountingCoverageService.BatchFact("NEW_EDGE_FACT", "INCREASE", BigDecimal.ONE)),
                        "INBOUND", "POST", policy -> 1));
        assertTrue(unknown.getMessage().contains("覆盖矩阵未登记"));

        int mapped = AccountingCoverageService.validateFacts(List.of(
                        new AccountingCoverageService.BatchFact("INVENTORY_ON_HAND", "DECREASE", new BigDecimal("80.50")),
                        new AccountingCoverageService.BatchFact("INVENTORY_IN_TRANSIT", "INCREASE", new BigDecimal("80.50"))),
                "INVENTORY_TRANSFER", "DISPATCH", policy -> 1);
        assertEquals(1, mapped, "在途配对事实必须明确不重复制证");
    }

    @Test
    void directTaxHandlersAndSourceReversalsRemainStableAndAuditable() throws Exception {
        String tax = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/service/TaxInvoiceService.java"));
        for (String source : List.of("TAX_INVOICE_BASE", "TAX_INVOICE_USAGE", "TAX_INVOICE_TRANSFER"))
            assertTrue(tax.contains(source), source);
        for (String account : List.of("5001", "22210102", "22210103", "22210101", "1405"))
            assertTrue(tax.contains("\"" + account + "\""), account);

        String controller = Files.readString(Path.of(
                "src/main/java/com/soyia/copperv2/controller/AccountingController.java"));
        assertTrue(controller.contains("/rule-coverage"));
        assertTrue(controller.contains("coverageService.matrix(asOf)"));
    }
}
