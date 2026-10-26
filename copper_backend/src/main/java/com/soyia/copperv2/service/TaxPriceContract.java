package com.soyia.copperv2.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Immutable ERP-04 price/tax calculation contract. Money is rounded per line with HALF_UP;
 * header discounts and adjustments are allocated by gross amount and the last line absorbs cents.
 */
public final class TaxPriceContract {
    public static final String POLICY = "LINE_GROSS_ALLOCATION_HALF_UP_V1";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private TaxPriceContract() {}

    public record Input(BigDecimal pricingQuantity, BigDecimal enteredUnitPrice, BigDecimal taxRate) {}

    public record Line(BigDecimal enteredUnitPrice, BigDecimal unitPriceExcludingTax,
                       BigDecimal taxRate, BigDecimal originalGrossAmount,
                       BigDecimal amountExcludingTax, BigDecimal taxAmount,
                       BigDecimal settlementTotalAmount, BigDecimal roundingDifference) {}

    public record Document(boolean priceIncludesTax, String taxRateSummary, String ruleSnapshot,
                           BigDecimal originalGrossAmount, BigDecimal discountAmount,
                           BigDecimal adjustmentAmount, BigDecimal amountExcludingTax,
                           BigDecimal taxAmount, BigDecimal totalAmount,
                           BigDecimal roundingDifference, List<Line> lines) {}

    public static Document calculate(boolean priceIncludesTax, List<Input> inputs,
                                     BigDecimal discountRate, BigDecimal adjustmentAmount,
                                     String sourceRuleSnapshot) {
        if (inputs == null || inputs.isEmpty()) throw new BusinessException("价税计算至少需要一条明细");
        List<BigDecimal> originalGross = new ArrayList<>();
        for (Input input : inputs) {
            BigDecimal quantity = nonNegative(input.pricingQuantity(), "计价数量");
            BigDecimal price = nonNegative(input.enteredUnitPrice(), "单价");
            BigDecimal rate = rate(input.taxRate());
            BigDecimal entered = money(quantity.multiply(price));
            BigDecimal gross = priceIncludesTax ? entered : entered.add(money(entered.multiply(rate).divide(HUNDRED)));
            originalGross.add(money(gross));
        }
        BigDecimal original = money(originalGross.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal normalizedDiscountRate = rate(discountRate);
        BigDecimal discount = money(original.multiply(normalizedDiscountRate).divide(HUNDRED));
        BigDecimal adjustment = money(adjustmentAmount);
        BigDecimal total = money(original.subtract(discount).add(adjustment));
        if (total.signum() < 0) throw new BusinessException("价税合计不能小于0");

        List<BigDecimal> allocatedGross = allocate(total, originalGross, original);
        List<Line> lines = new ArrayList<>();
        Map<BigDecimal, BigDecimal> netByRate = new LinkedHashMap<>();
        Map<BigDecimal, BigDecimal> taxByRate = new LinkedHashMap<>();
        for (int i = 0; i < inputs.size(); i++) {
            Input input = inputs.get(i);
            BigDecimal rate = rate(input.taxRate());
            BigDecimal gross = allocatedGross.get(i);
            BigDecimal net = rate.signum() == 0 ? gross
                    : money(gross.divide(BigDecimal.ONE.add(rate.divide(HUNDRED, 8, RoundingMode.HALF_UP)), 8, RoundingMode.HALF_UP));
            BigDecimal tax = money(gross.subtract(net));
            BigDecimal expectedLineTax = money(net.multiply(rate).divide(HUNDRED));
            BigDecimal unitNet = priceIncludesTax && rate.signum() > 0
                    ? input.enteredUnitPrice().divide(BigDecimal.ONE.add(rate.divide(HUNDRED, 8, RoundingMode.HALF_UP)), 6, RoundingMode.HALF_UP)
                    : input.enteredUnitPrice().setScale(6, RoundingMode.HALF_UP);
            lines.add(new Line(input.enteredUnitPrice(), unitNet, rate, originalGross.get(i),
                    net, tax, gross, money(tax.subtract(expectedLineTax))));
            netByRate.merge(rate, net, BigDecimal::add);
            taxByRate.merge(rate, tax, BigDecimal::add);
        }
        BigDecimal net = money(lines.stream().map(Line::amountExcludingTax).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal tax = money(lines.stream().map(Line::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal groupedTax = money(netByRate.entrySet().stream()
                .map(entry -> money(entry.getValue().multiply(entry.getKey()).divide(HUNDRED)))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        String summary = taxByRate.entrySet().stream()
                .map(entry -> entry.getKey().setScale(4, RoundingMode.HALF_UP).toPlainString() + "%:" + money(entry.getValue()).toPlainString())
                .collect(Collectors.joining(";"));
        String source = sourceRuleSnapshot == null || sourceRuleSnapshot.isBlank() ? "DIRECT_PARTNER_PROFILE" : sourceRuleSnapshot.trim();
        String policyPrefix = POLICY + "|";
        if (source.startsWith(policyPrefix)) {
            int sourceStart = source.indexOf('|', policyPrefix.length());
            source = sourceStart < 0 ? "DIRECT_PARTNER_PROFILE" : source.substring(sourceStart + 1);
        }
        String rule = POLICY + "|" + (priceIncludesTax ? "TAX_INCLUDED" : "TAX_EXCLUDED") + "|" + source;
        return new Document(priceIncludesTax, summary, rule, original, discount, adjustment,
                net, tax, total, money(tax.subtract(groupedTax)), List.copyOf(lines));
    }

    private static List<BigDecimal> allocate(BigDecimal total, List<BigDecimal> weights, BigDecimal weightTotal) {
        List<BigDecimal> result = new ArrayList<>();
        BigDecimal allocated = ZERO;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal amount;
            if (i == weights.size() - 1) amount = money(total.subtract(allocated));
            else if (weightTotal.signum() == 0) amount = ZERO;
            else amount = money(total.multiply(weights.get(i)).divide(weightTotal, 8, RoundingMode.HALF_UP));
            result.add(amount);
            allocated = allocated.add(amount);
        }
        return result;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0) throw new BusinessException(label + "不能小于0");
        return normalized;
    }

    private static BigDecimal rate(BigDecimal value) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.signum() < 0 || normalized.compareTo(HUNDRED) > 0)
            throw new BusinessException("税率必须在0%到100%之间");
        return normalized.setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }
}
