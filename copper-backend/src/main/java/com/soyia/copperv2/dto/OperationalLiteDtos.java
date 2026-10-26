package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public final class OperationalLiteDtos {
    private OperationalLiteDtos() {}

    public record FollowupItemSave(
            @NotNull Long purchaseOrderItemId,
            @NotNull @DecimalMin("0.000001") BigDecimal dispatchedQuantity
    ) {}

    public record FollowupEventSave(
            @NotBlank @Size(max = 24) String eventType,
            @NotNull LocalDate occurredOn,
            LocalDate expectedArrivalDate,
            @Size(max = 128) String carrierName,
            @Size(max = 128) String trackingNo,
            @Size(max = 32) String exceptionType,
            @Size(max = 500) String reason,
            @Valid List<FollowupItemSave> items
    ) {}

    public record FollowupEventItem(
            Long id, Long purchaseOrderItemId, String productSku, String productName,
            BigDecimal dispatchedQuantity, String unit
    ) {}

    public record FollowupEvent(
            Long id, String eventType, LocalDate occurredOn, LocalDate expectedArrivalDate,
            String carrierName, String trackingNo, String exceptionType, String reason,
            String operatorName, LocalDateTime createdAt, List<FollowupEventItem> items
    ) {}

    public record FollowupItemSummary(
            Long purchaseOrderItemId, String productSku, String productName, String unit,
            BigDecimal orderedQuantity, BigDecimal dispatchedQuantity, BigDecimal receivedQuantity,
            BigDecimal inTransitQuantity, BigDecimal remainingToDispatch
    ) {}

    public record FollowupSummary(
            Long purchaseOrderId, String purchaseOrderNo, String supplierName, String orderStatus,
            String fulfillmentStatus, LocalDate latestExpectedArrivalDate, Integer delayDays,
            boolean openException, String latestException, BigDecimal orderedQuantity,
            BigDecimal dispatchedQuantity, BigDecimal receivedQuantity, BigDecimal inTransitQuantity,
            BigDecimal remainingToDispatch, List<FollowupItemSummary> items,
            List<FollowupEvent> events
    ) {}

    public record CashWindowSource(
            String sourceType, Long sourceId, String sourceNo, String partnerName,
            LocalDate dueDate, String direction, String factLevel, BigDecimal amount,
            String explanation
    ) {}

    public record CashWindowDay(
            LocalDate date, BigDecimal inflow, BigDecimal outflow,
            BigDecimal conservativeBalance, BigDecimal scheduledBalance,
            BigDecimal conservativeGap, BigDecimal scheduledGap
    ) {}

    public record CashWindow(
            LocalDateTime snapshotAt, LocalDate startDate, LocalDate endDate,
            BigDecimal openingCash, BigDecimal receivable30Days, BigDecimal payable30Days,
            BigDecimal conservativeGap7Days, BigDecimal scheduledGap7Days,
            BigDecimal conservativeGap30Days, BigDecimal scheduledGap30Days,
            LocalDate firstConservativeGapDate, LocalDate firstScheduledGapDate,
            List<CashWindowDay> days, List<CashWindowSource> sources,
            String formula
    ) {}

    public record ConfirmationPacketCreate(
            @NotBlank @Size(max = 32) String packetType,
            @NotNull Long sourceId,
            @NotNull LocalDate asOfDate,
            @NotNull LocalDateTime expiresAt
    ) {}

    public record ConfirmationPacketRow(
            Long id, String packetNo, String packetType, String sourceType, Long sourceId,
            String sourceNo, Long partnerId, String partnerName, LocalDate asOfDate,
            String snapshotHash, LocalDateTime expiresAt, String status,
            String createdByName, Long viewCount, LocalDateTime lastViewedAt,
            String revokeReason, LocalDateTime createdAt, String decision,
            String confirmerName, String comment, LocalDateTime confirmedAt
    ) {}

    public record ConfirmationPacketCreated(ConfirmationPacketRow packet, String token) {}

    public record PublicConfirmationPacket(
            String packetNo, String packetType, String sourceNo, String partnerName,
            LocalDate asOfDate, String snapshotHash, LocalDateTime expiresAt,
            String status, Map<String, Object> snapshot, ConfirmationResult confirmation
    ) {}

    public record PublicConfirmationSave(
            @NotBlank @Size(max = 16) String decision,
            @NotBlank @Size(max = 128) String confirmerName,
            @Size(max = 16) String contactTail,
            @Size(max = 1000) String comment
    ) {}

    public record ConfirmationResult(
            String decision, String confirmerName, String contactTail,
            String comment, String snapshotHash, LocalDateTime confirmedAt
    ) {}

    public record IntakeCandidate(
            Long id, String fieldCode, String fieldLabel, String candidateValue,
            String confirmedValue, BigDecimal confidence, String sourceExcerpt, Integer sortNo
    ) {}

    public record IntakeBatch(
            Long id, String intakeNo, String documentType, String originalFileName,
            String contentType, Long fileSize, String sha256, String extractionMode,
            String ocrEngine, String ocrModel, String ocrResponseSha256,
            String status, String errorMessage, String uploadedByName, String reviewedByName,
            LocalDateTime reviewedAt, String reviewNote, LocalDateTime createdAt,
            List<IntakeCandidate> candidates
    ) {}

    public record IntakeCandidateReview(
            @NotBlank @Size(max = 64) String fieldCode,
            @Size(max = 1000) String confirmedValue
    ) {}

    public record IntakeReview(
            @NotBlank @Size(max = 16) String decision,
            @Size(max = 500) String note,
            @NotEmpty @Valid List<IntakeCandidateReview> candidates
    ) {}
}
