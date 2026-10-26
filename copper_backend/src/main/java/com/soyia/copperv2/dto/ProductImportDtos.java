package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class ProductImportDtos {
    private ProductImportDtos() {
    }

    public record TemplateInfo(
            String templateVersion,
            Integer maxRows,
            Long maxFileSize,
            List<TemplateColumn> columns,
            List<SeriesOption> series,
            List<UnitOption> units
    ) {
    }

    public record TemplateColumn(
            String key,
            String label,
            Boolean required,
            String description
    ) {
    }

    public record SeriesOption(
            Long id,
            String name,
            String categoryName,
            List<String> aliases,
            String status,
            List<SeriesFieldOption> fields
    ) {
    }

    public record SeriesFieldOption(
            Long seriesFieldId,
            String key,
            String name,
            String displayName,
            String fieldType,
            String referenceType,
            String unitName,
            Boolean required,
            Boolean skuDimension,
            Boolean includeInSpec,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer decimalScale,
            List<String> options
    ) {
    }

    public record UnitOption(
            Long id,
            String name,
            String abbreviation,
            String canonicalValue,
            String categoryName
    ) {
    }

    public record JobSummary(
            Long id,
            String jobNo,
            String templateVersion,
            String sourceFileName,
            String sourceFileFormat,
            String sourceFileSha256,
            Long sourceFileSize,
            Map<String, String> mapping,
            String status,
            Boolean recoverable,
            LocalDateTime leaseExpiresAt,
            Integer totalRows,
            Integer createRows,
            Integer safeUpdateRows,
            Integer skipRows,
            Integer conflictRows,
            Integer invalidRows,
            Integer committedRows,
            Integer failedRows,
            Integer lockVersion,
            String importedByName,
            LocalDateTime createdAt,
            LocalDateTime completedAt
    ) {
    }

    public record RowView(
            Long id,
            Long jobId,
            Integer sourceRowNo,
            Map<String, String> rawData,
            Map<String, Object> normalizedData,
            String sourceHash,
            String productSignature,
            String validationStatus,
            String validationMessage,
            String proposedAction,
            String decidedAction,
            String decisionReason,
            Long targetProductId,
            String targetProductSku,
            String commitStatus,
            String commitError,
            Long importedProductId,
            Integer rowVersion
    ) {
    }

    public record ConflictView(
            Long id,
            Long jobId,
            Long rowId,
            String conflictType,
            String fieldKey,
            String sourceValue,
            String targetValue,
            String severity,
            String resolutionStatus,
            String resolutionNote,
            String message
    ) {
    }

    public record Preview(
            JobSummary job,
            List<RowView> rows,
            List<ConflictView> conflicts,
            Boolean idempotentReplay
    ) {
    }

    public record RowDecision(
            @NotBlank String action,
            Long targetProductId,
            String reason,
            @NotNull Integer expectedRowVersion
    ) {
    }

    public record CommitRequest(
            @NotNull Integer expectedLockVersion,
            Boolean allowSafeUpdates
    ) {
    }

    public record CommitResult(
            Long jobId,
            String status,
            Integer createdRows,
            Integer updatedRows,
            Integer skippedRows,
            Integer failedRows,
            Integer lockVersion,
            List<Long> importedProductIds
    ) {
    }
}
