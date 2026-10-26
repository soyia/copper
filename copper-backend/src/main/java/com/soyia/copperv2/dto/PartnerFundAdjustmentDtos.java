package com.soyia.copperv2.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PartnerFundAdjustmentDtos {
    private PartnerFundAdjustmentDtos() {}

    public record Create(
            @NotNull Long partnerId,
            @NotBlank String accountType,
            @NotBlank String adjustmentType,
            @NotBlank String direction,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull LocalDate businessDate,
            @NotBlank @Size(max = 128) String basisNo,
            @NotBlank String taxTreatment,
            @NotBlank @Size(min = 4, max = 500) String memo
    ) {}

    public record Action(
            @NotBlank @Size(min = 4, max = 500) String reason,
            LocalDate businessDate,
            Long approvalId
    ) {
        public Action(String reason, LocalDate businessDate) {
            this(reason, businessDate, null);
        }
    }

    public record Row(
            Long id,
            String adjustmentNo,
            Long partnerId,
            String partnerName,
            String accountType,
            String adjustmentType,
            String direction,
            BigDecimal amount,
            LocalDate businessDate,
            String basisNo,
            String taxTreatment,
            String memo,
            String status,
            String createdByName,
            String confirmedByName,
            LocalDateTime confirmedAt,
            String cancelReason,
            String cancelledByName,
            LocalDateTime cancelledAt,
            String reversalNo,
            String reversalReason,
            String reversedByName,
            LocalDateTime reversedAt,
            Long postingBatchId,
            String postingNo,
            String postingStatus,
            Long accountingVoucherId,
            String accountingVoucherNo,
            String accountingVoucherStatus,
            LocalDateTime createdAt
    ) {}

    public record EventRow(
            Long id,
            String action,
            String fromStatus,
            String toStatus,
            String reason,
            String operatorName,
            LocalDateTime createdAt
    ) {}

    public record Detail(Row header, List<EventRow> events) {}
}
