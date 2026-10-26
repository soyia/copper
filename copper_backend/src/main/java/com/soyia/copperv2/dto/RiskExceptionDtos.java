package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class RiskExceptionDtos {
    private RiskExceptionDtos() {}

    public record Policy(
            Long entityId,
            BigDecimal largePaymentThreshold,
            BigDecimal inventoryAdjustmentThreshold,
            BigDecimal highRiskReversalThreshold,
            Integer approvalValidHours,
            Integer versionNo,
            String changeReason,
            String updatedByName,
            LocalDateTime updatedAt
    ) {}

    public record PolicySave(
            @NotNull @DecimalMin("0.01") BigDecimal largePaymentThreshold,
            @NotNull @DecimalMin("0.01") BigDecimal inventoryAdjustmentThreshold,
            @NotNull @DecimalMin("0.01") BigDecimal highRiskReversalThreshold,
            @NotNull @Min(1) @Max(720) Integer approvalValidHours,
            @NotBlank @Size(min = 4, max = 500) String changeReason
    ) {}

    public record SourceSubmit(
            @NotBlank String sourceType,
            @NotNull Long sourceId,
            @NotBlank String actionCode,
            @NotBlank @Size(min = 8, max = 500) String reason
    ) {}

    public record SupplierPaymentIntentSubmit(
            @NotNull @Valid SupplierPaymentCreateRequest payment,
            @NotBlank @Size(min = 8, max = 500) String reason
    ) {}

    public record InlineAuthorization(
            @Size(max = 500) String reason,
            Long approvalId
    ) {}

    public record Decision(
            @NotBlank @Size(min = 4, max = 500) String reason
    ) {}

    public record Transfer(
            @NotNull Long targetEmployeeId,
            @NotBlank @Size(min = 4, max = 500) String reason
    ) {}

    public record RequestRow(
            Long id,
            String requestNo,
            Integer policyVersion,
            String exceptionType,
            String riskLevel,
            String sourceType,
            Long sourceId,
            String sourceNo,
            String actionCode,
            Integer sourceVersion,
            BigDecimal sourceAmount,
            String currency,
            String title,
            String requestReason,
            String snapshotHash,
            String status,
            Long assignedEmployeeId,
            String assignedEmployeeName,
            Long requestedEmployeeId,
            String requestedByName,
            LocalDateTime requestedAt,
            LocalDateTime expiresAt,
            Long approvedEmployeeId,
            String approvedByName,
            LocalDateTime approvedAt,
            String decisionReason,
            String rejectedByName,
            LocalDateTime rejectedAt,
            String withdrawnByName,
            LocalDateTime withdrawnAt,
            String consumedByName,
            LocalDateTime consumedAt,
            String resultSourceType,
            Long resultSourceId,
            String resultSourceNo,
            Integer versionNo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record EventRow(
            Long id,
            String action,
            String fromStatus,
            String toStatus,
            String reason,
            Long operatorEmployeeId,
            String operatorName,
            LocalDateTime createdAt
    ) {}

    public record RequestDetail(RequestRow header, String snapshotJson, List<EventRow> events) {}

    public record SourceStatus(
            Boolean required,
            String reason,
            Boolean canDirectApprove,
            RequestRow activeRequest
    ) {}

    public record Approver(
            Long employeeId,
            String employeeNo,
            String employeeName,
            String departmentName,
            String jobTitle
    ) {}

    public record Dashboard(
            Long pendingCount,
            Long approvedWaitingExecutionCount,
            Long expiringWithin24HoursCount,
            Long requestedByMeCount
    ) {}
}
