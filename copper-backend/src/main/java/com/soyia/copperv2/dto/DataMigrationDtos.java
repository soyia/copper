package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class DataMigrationDtos {
    private DataMigrationDtos() {}

    public record BatchRow(
            Long id,
            String batchNo,
            LocalDate openingDate,
            String sourceFileName,
            String sourceFileSha256,
            Long sourceFileSize,
            String status,
            Integer totalRows,
            Integer validRows,
            Integer errorRows,
            Integer appliedRows,
            Long openingVoucherId,
            String openingVoucherNo,
            String openingVoucherStatus,
            String failureMessage,
            String importedByName,
            String appliedByName,
            String cutoverByName,
            String rolledBackByName,
            LocalDateTime preflightAt,
            LocalDateTime appliedAt,
            LocalDateTime cutoverAt,
            LocalDateTime rolledBackAt,
            LocalDateTime lastCheckedAt,
            LocalDateTime createdAt
    ) {}

    public record DomainSummary(
            String domainCode,
            String domainName,
            Integer totalRows,
            Integer validRows,
            Integer errorRows,
            Integer appliedRows,
            BigDecimal quantityTotal,
            BigDecimal amountTotal
    ) {}

    public record RowResult(
            Long id,
            String domainCode,
            String sourceSheet,
            Integer sourceRowNo,
            String sourceKey,
            String validationStatus,
            String errorCode,
            String errorMessage,
            String targetType,
            Long targetId,
            Boolean targetCreated,
            LocalDateTime appliedAt,
            LocalDateTime rolledBackAt
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

    public record CheckIssue(
            Long id,
            String checkCode,
            String severity,
            String domainCode,
            String expectedValue,
            String actualValue,
            String message
    ) {}

    public record CheckRun(
            Long id,
            String triggerType,
            String status,
            Integer checkedCount,
            Integer issueCount,
            Map<String, Object> summary,
            String checkedByName,
            LocalDateTime createdAt,
            List<CheckIssue> issues
    ) {}

    public record Detail(
            BatchRow header,
            List<DomainSummary> domains,
            List<RowResult> rowErrors,
            List<EventRow> events,
            CheckRun latestCheck,
            List<String> safeguards
    ) {}

    public record Action(
            @NotBlank @Size(min = 4, max = 500) String reason
    ) {}
}
