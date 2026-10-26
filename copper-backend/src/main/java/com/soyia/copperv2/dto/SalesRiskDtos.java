package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class SalesRiskDtos {
    private SalesRiskDtos() {}

    public record Policy(
            Long partnerId, String partnerName, boolean enabled,
            boolean creditControlEnabled, BigDecimal creditLimit,
            boolean overdueControlEnabled, Integer maxOverdueDays,
            boolean marginControlEnabled, BigDecimal minimumMarginRate,
            Integer versionNo, String changeReason, Long updatedBy,
            String updatedByName, LocalDateTime updatedAt
    ) {}

    public record PolicySave(
            @NotNull Boolean enabled,
            @NotNull Boolean creditControlEnabled,
            @NotNull @DecimalMin("0.00") BigDecimal creditLimit,
            @NotNull Boolean overdueControlEnabled,
            @NotNull @Min(0) @Max(3650) Integer maxOverdueDays,
            @NotNull Boolean marginControlEnabled,
            @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal minimumMarginRate,
            @NotBlank String changeReason
    ) {}

    public record OverrideRequest(String reason, Long approvalId) {
        public OverrideRequest(String reason) {
            this(reason, null);
        }
    }

    public record Exposure(
            BigDecimal receivableAmount,
            BigDecimal confirmedUnshippedAmount,
            BigDecimal currentDocumentAmount,
            BigDecimal grossExposureAmount,
            BigDecimal advanceCreditAmount,
            BigDecimal acceptanceSecuredAmount,
            BigDecimal netExposureAmount,
            BigDecimal creditLimitAmount,
            BigDecimal availableCreditAmount,
            BigDecimal overLimitAmount,
            BigDecimal overdueAmount,
            Integer maximumOverdueDays
    ) {}

    public record Margin(
            boolean costAvailable,
            BigDecimal revenueExcludingTax,
            BigDecimal estimatedCostAmount,
            BigDecimal estimatedGrossProfit,
            BigDecimal estimatedMarginRate,
            BigDecimal minimumMarginRate,
            String costBasis
    ) {}

    public record Issue(String code, String title, String message) {}

    public record Evaluation(
            String documentType, Long documentId, String documentNo, String action,
            boolean policyEnabled, Integer policyVersion, String decision,
            boolean blocked, boolean canOverride, boolean marginVisible,
            Exposure exposure, Margin margin, List<Issue> issues,
            LocalDateTime evaluatedAt
    ) {}

    public record Snapshot(
            Long id, String documentType, Long documentId, String documentNo,
            String action, Integer sourceRevisionNo, Long customerId,
            Integer policyVersion, Exposure exposure, Margin margin,
            List<String> issueCodes, String decision, String overrideReason,
            Long operatorId, String operatorName, LocalDateTime createdAt
    ) {}
}
