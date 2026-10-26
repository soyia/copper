package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PriceCenterDtos {
    private PriceCenterDtos() {}

    public record BenchmarkRow(
            Long id, String benchmarkCode, String benchmarkName, Integer versionNo,
            String priceUnit, BigDecimal unitPrice, boolean priceIncludesTax, BigDecimal taxRate,
            LocalDate effectiveDate, String sourceType, String sourceNote, String changeReason,
            Long operatorId, String operatorName, LocalDateTime createdAt
    ) {}

    public record BenchmarkSave(
            String benchmarkCode, @NotBlank String benchmarkName, @NotBlank String priceUnit,
            @NotNull @DecimalMin(value = "0.0000", inclusive = false) BigDecimal unitPrice, boolean priceIncludesTax,
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal taxRate,
            @NotNull LocalDate effectiveDate, @NotBlank String sourceType,
            String sourceNote, @NotBlank String changeReason
    ) {}

    public record RuleRow(
            Long id, String ruleCode, String ruleName, Integer versionNo,
            String businessType, String scopeType, Long partnerId, String partnerName,
            String customerLevel, String agreementType, Long productId, String productSku,
            String productName, String productSpec, String priceUnit,
            BigDecimal minimumQuantity, BigDecimal maximumQuantity, String pricingMode,
            String benchmarkCode, BigDecimal fixedUnitPrice, BigDecimal processingFee,
            BigDecimal specificationFee, BigDecimal packagingFee, BigDecimal freightFee,
            boolean priceIncludesTax, BigDecimal taxRate, LocalDate effectiveFrom,
            LocalDate effectiveTo, String changeReason, String status,
            Long operatorId, String operatorName, LocalDateTime createdAt
    ) {}

    public record RuleSave(
            String ruleCode, @NotBlank String ruleName, @NotBlank String businessType,
            @NotBlank String scopeType, Long partnerId, String customerLevel,
            @NotBlank String agreementType, @NotNull Long productId, @NotBlank String priceUnit,
            @NotNull @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal minimumQuantity,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal maximumQuantity,
            @NotBlank String pricingMode,
            String benchmarkCode, @DecimalMin("0.0000") BigDecimal fixedUnitPrice,
            @DecimalMin("0.0000") BigDecimal processingFee,
            @DecimalMin("0.0000") BigDecimal specificationFee,
            @DecimalMin("0.0000") BigDecimal packagingFee,
            @DecimalMin("0.0000") BigDecimal freightFee,
            boolean priceIncludesTax,
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal taxRate,
            @NotNull LocalDate effectiveFrom, LocalDate effectiveTo,
            @NotBlank String changeReason
    ) {}

    public record PreviewRequest(
            @NotBlank String businessType, Long partnerId, @NotNull Long productId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            @NotBlank String priceUnit,
            @NotNull LocalDate businessDate
    ) {}

    public record Component(String code, String name, BigDecimal sourceAmount,
                            BigDecimal unitPriceContribution) {}

    public record Preview(
            BigDecimal unitPrice, String priceUnit, String sourceCode, String sourceName,
            String explanation, Long ruleVersionId, String ruleCode, Integer ruleVersionNo,
            Long benchmarkVersionId, String benchmarkCode, Integer benchmarkVersionNo,
            boolean priceIncludesTax, BigDecimal taxRate, String agreementType,
            List<Component> components, List<String> warnings, String snapshotJson
    ) {}

    public record PriceSnapshot(String sourceCode, String sourceName, Long ruleVersionId,
                                String snapshotJson) {}
}
