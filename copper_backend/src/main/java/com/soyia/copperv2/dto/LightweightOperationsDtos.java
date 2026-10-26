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

public final class LightweightOperationsDtos {
    private LightweightOperationsDtos() {}

    public record ReplenishmentRow(
            Long productId, String sku, String productName, Long warehouseId, String warehouseName,
            String baseUnit, BigDecimal onHandQuantity, BigDecimal reservedQuantity,
            BigDecimal freeQuantity, BigDecimal incomingQuantity, BigDecimal sales30Days,
            BigDecimal averageDailySales, Integer leadTimeDays, Integer targetCoverageDays,
            BigDecimal coverageDays, BigDecimal suggestedQuantity, LocalDate oldestBatchDate,
            Integer oldestBatchAgeDays, BigDecimal stockAmount, String attentionLevel,
            String explanation
    ) {}

    public record ContributionRow(
            String sourceType, Long sourceId, String sourceNo, Long partnerId, String partnerName,
            LocalDate businessDate, String status, BigDecimal revenue, BigDecimal inventoryCost,
            BigDecimal allocatedExpense, BigDecimal grossMargin, BigDecimal contributionMargin,
            BigDecimal outstandingAmount
    ) {}

    public record OwnerSummary(
            LocalDate businessDate, BigDecimal todaySales, BigDecimal monthSales,
            BigDecimal monthContributionMargin, BigDecimal availableCashAndBank,
            BigDecimal overdueReceivable, BigDecimal duePayable7Days,
            BigDecimal agedStockAmount, Long pendingDeliveryCount,
            Long pendingQualityCount, Long replenishmentAttentionCount
    ) {}

    public record QuoteItemSave(
            @NotNull Long productId,
            @NotNull @DecimalMin("0.000001") BigDecimal requestedQuantity,
            @NotBlank @Size(max = 64) String requestedUnit,
            @NotNull @DecimalMin("0.00000000") BigDecimal quotedUnitPrice
    ) {}

    public record QuoteSave(
            @NotNull Long supplierId,
            @NotNull LocalDate quoteDate,
            @NotNull Boolean priceIncludesTax,
            @NotNull @DecimalMin("0.00") BigDecimal freightAmount,
            @NotNull Integer leadTimeDays,
            @Size(max = 255) String settlementTerms,
            @Size(max = 500) String sourceNote,
            @NotEmpty @Valid List<QuoteItemSave> items
    ) {}

    public record QuoteItem(
            Long id, Long productId, String productSku, String productName,
            BigDecimal requestedQuantity, String requestedUnit,
            BigDecimal quotedUnitPrice, BigDecimal amount
    ) {}

    public record QuoteOption(
            Long id, Long purchaseOrderId, Long supplierId, String supplierName,
            LocalDate quoteDate, Boolean priceIncludesTax, BigDecimal goodsAmount,
            BigDecimal freightAmount, BigDecimal totalAmount, Integer leadTimeDays,
            String settlementTerms, String sourceNote, String status,
            String selectedByName, LocalDateTime selectedAt, String voidReason,
            List<QuoteItem> items
    ) {}

    public record Action(@NotBlank @Size(min = 2, max = 500) String reason) {}

    public record SalesCostSave(
            @NotNull Long treasuryDocumentId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank @Size(max = 500) String reason
    ) {}

    public record SalesCostAllocation(
            Long id, Long treasuryDocumentId, String treasuryDocumentNo,
            String treasuryDocumentType, String summary, BigDecimal documentAmount,
            BigDecimal documentAllocatedAmount, String sourceType, Long sourceId,
            BigDecimal amount, String reason, String status, String createdByName,
            String reversalReason, String reversedByName, LocalDateTime reversedAt,
            LocalDateTime createdAt
    ) {}

    public record ExpenseSource(
            Long id, String documentNo, String documentType, Long partnerId,
            String partnerName, LocalDate businessDate, String summary,
            BigDecimal totalAmount, BigDecimal allocatedAmount,
            BigDecimal availableAmount, String status
    ) {}

    public record DeliverySave(
            @NotBlank String deliveryMode,
            @Size(max = 128) String carrierName,
            @Size(max = 128) String trackingNo,
            @Size(max = 128) String handlerName,
            @Size(max = 500) String memo
    ) {}

    public record DeliveryAction(
            @NotBlank String action,
            @Size(max = 128) String recipientName,
            @Size(max = 500) String reason
    ) {}

    public record DeliveryEvent(
            Long id, String action, String fromStatus, String toStatus,
            String reason, String operatorName, LocalDateTime createdAt
    ) {}

    public record Delivery(
            Long id, Long outboundId, String outboundNo, String deliveryMode,
            String carrierName, String trackingNo, String handlerName, String status,
            LocalDateTime dispatchedAt, LocalDateTime signedAt, String recipientName,
            String exceptionNote, String memo, Integer versionNo,
            String createdByName, String updatedByName, LocalDateTime createdAt,
            LocalDateTime updatedAt, List<DeliveryEvent> events
    ) {}

    public record ShareCreate(
            @NotBlank String sourceType,
            @NotNull Long sourceId,
            @NotNull LocalDateTime expiresAt
    ) {}

    public record ShareRow(
            Long id, String shareNo, String sourceType, Long sourceId, String sourceNo,
            Long partnerId, String partnerName, LocalDateTime expiresAt, String status,
            String createdByName, Long viewCount, LocalDateTime lastViewedAt,
            String revokeReason, LocalDateTime createdAt
    ) {}

    public record ShareCreated(ShareRow share, String token) {}

    public record PublicOrderLine(
            String productSku, String productName, String productSpec,
            BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount
    ) {}

    public record PublicOrder(
            String sourceType, String sourceNo, String partnerName, LocalDate businessDate,
            String status, Boolean priceIncludesTax, BigDecimal totalAmount,
            String memo, List<PublicOrderLine> items
    ) {}

    public record ApiKeyCreate(
            @NotBlank @Size(max = 128) String keyName,
            @NotNull Long partnerId,
            @NotNull LocalDateTime expiresAt
    ) {}

    public record ApiKeyRow(
            Long id, String keyNo, String keyName, Long partnerId, String partnerName,
            String scopes, LocalDateTime expiresAt, String status, String createdByName,
            Long useCount, LocalDateTime lastUsedAt, String revokeReason,
            LocalDateTime createdAt
    ) {}

    public record ApiKeyCreated(ApiKeyRow key, String token) {}
}
