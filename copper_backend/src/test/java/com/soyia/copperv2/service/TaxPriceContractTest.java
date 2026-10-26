package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaxPriceContractTest {
    @Test
    void inclusivePriceAllocatesDiscountAdjustmentAndKeepsGrossEquation() {
        TaxPriceContract.Document document = TaxPriceContract.calculate(true, List.of(
                input("3", "113", "13"), input("2", "106", "6")
        ), decimal("10"), decimal("0.01"), "QUOTE Q-1 REV-2");

        assertEquals(decimal("551.00"), document.originalGrossAmount());
        assertEquals(decimal("55.10"), document.discountAmount());
        assertEquals(decimal("495.91"), document.totalAmount());
        assertEquals(document.totalAmount(), document.amountExcludingTax().add(document.taxAmount()));
        assertEquals(document.totalAmount(), document.lines().stream()
                .map(TaxPriceContract.Line::settlementTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        assertTrue(document.ruleSnapshot().contains(TaxPriceContract.POLICY));
        assertTrue(document.ruleSnapshot().contains("QUOTE Q-1 REV-2"));
    }

    @Test
    void exclusivePriceAddsTaxAndLastLineAbsorbsCentResidual() {
        TaxPriceContract.Document document = TaxPriceContract.calculate(false, List.of(
                input("1", "0.05", "13"), input("1", "0.05", "13"), input("1", "0.05", "13")
        ), BigDecimal.ZERO, BigDecimal.ZERO, "DIRECT");

        assertEquals(decimal("0.18"), document.totalAmount());
        assertEquals(document.totalAmount(), document.amountExcludingTax().add(document.taxAmount()));
        assertEquals(document.totalAmount(), document.lines().stream()
                .map(TaxPriceContract.Line::settlementTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void recalculatingAStoredContractKeepsTheRuleSnapshotStable() {
        TaxPriceContract.Document original = TaxPriceContract.calculate(true,
                List.of(input("1", "113", "13")), BigDecimal.ZERO, BigDecimal.ZERO,
                "PARTNER_PROFILE|CUSTOMER|DIGITAL_SPECIAL");
        TaxPriceContract.Document recalculated = TaxPriceContract.calculate(true,
                List.of(input("1", "113", "13")), BigDecimal.ZERO, BigDecimal.ZERO,
                original.ruleSnapshot());
        TaxPriceContract.Document changedMode = TaxPriceContract.calculate(false,
                List.of(input("1", "100", "13")), BigDecimal.ZERO, BigDecimal.ZERO,
                original.ruleSnapshot());

        assertEquals(original.ruleSnapshot(), recalculated.ruleSnapshot());
        assertTrue(changedMode.ruleSnapshot().contains("|TAX_EXCLUDED|PARTNER_PROFILE|CUSTOMER|DIGITAL_SPECIAL"));
    }

    private TaxPriceContract.Input input(String quantity, String price, String rate) {
        return new TaxPriceContract.Input(decimal(quantity), decimal(price), decimal(rate));
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value).setScale(2);
    }
}
