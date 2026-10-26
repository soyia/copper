package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class PaymentAllocationDtos {
    private PaymentAllocationDtos() {
    }

    public record AllocationInput(
            @NotBlank String targetType,
            @NotNull Long targetId,
            @NotNull @DecimalMin("0.01") BigDecimal amount
    ) {
    }

    public record AllocationChange(
            @NotBlank String reason,
            @NotNull LocalDate businessDate,
            @NotNull List<Long> releaseAllocationIds,
            @Valid @NotNull List<AllocationInput> allocations
    ) {
    }

    public record AllocationSummary(
            Long paymentId,
            String paymentNo,
            BigDecimal amount,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount,
            String verificationStatus
    ) {
    }

    public record AllocationRow(
            Long id,
            String allocationNo,
            String targetType,
            Long targetId,
            String targetNo,
            BigDecimal allocatedAmount,
            String status,
            String captureSource,
            String releaseReason,
            LocalDateTime releasedAt,
            String reversalReason,
            LocalDateTime reversedAt,
            LocalDateTime createdAt
    ) {
    }

    public record EventRow(
            Long id,
            Long allocationId,
            String action,
            String fromStatus,
            String toStatus,
            BigDecimal amount,
            LocalDate businessDate,
            String reason,
            Long operatorId,
            String operatorName,
            LocalDateTime createdAt
    ) {
    }

    public record Detail(
            Long paymentId,
            String paymentNo,
            String partnerType,
            Long partnerId,
            String partnerName,
            BigDecimal amount,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount,
            String paymentMethod,
            Long businessBankAccountId,
            String businessBankAccountName,
            LocalDate businessDate,
            String verificationStatus,
            String fundNature,
            String allocationMode,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt,
            List<AllocationRow> allocations,
            List<EventRow> events
    ) {
    }
}
