package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class InventoryDtos {
    private InventoryDtos() {}

    public record Action(@NotNull @Size(min = 1, max = 500) String reason, Long approvalId) {
        public Action(String reason) {
            this(reason, null);
        }
    }

    public record CostPolicy(
            String policyCode, String costingMethod, Integer quantityScale, Integer unitCostScale,
            Integer amountScale, String roundingMode, String retroactiveRecalculation, LocalDate effectiveFrom
    ) {}

    public record OpeningCostAlignment(
            Long id, Long batchId, String batchNo, Long productId, String sku, String productName,
            Long warehouseId, String warehouseName, BigDecimal quantitySnapshot, BigDecimal unitCostSnapshot,
            BigDecimal batchAmountBefore, BigDecimal batchAmountAfter, BigDecimal differenceAmount,
            BigDecimal stockBalanceAmountSnapshot, String alignmentReason, LocalDateTime alignedAt
    ) {}

    public record Movement(
            Long id, String movementNo, String movementType, Long productId, String productName,
            Long warehouseId, String warehouseName, Long batchId, String batchNo,
            BigDecimal quantity, BigDecimal unitCost, BigDecimal amount,
            String refType, Long refId, String sourceDocumentType, Long sourceDocumentId,
            String sourceDocumentNo, String memo, LocalDateTime occurredAt
    ) {}

    public record ReconciliationRow(
            Long productId, String sku, String productName, Long warehouseId, String warehouseName,
            BigDecimal batchQuantity, BigDecimal balanceQuantity, BigDecimal quantityDifference,
            BigDecimal reservedQuantity, BigDecimal freeQuantity, BigDecimal inTransitQuantity,
            BigDecimal trackedBatchQuantity, BigDecimal trackedPackageQuantity, BigDecimal packageDifference,
            BigDecimal batchAmountCost, BigDecimal balanceAmountCost, BigDecimal costDifference,
            long negativeSourceCount, long unavailableBatchCount, String status
    ) {}

    public record Event(
            Long id, String documentType, Long documentId, String action, String reason,
            Long operatorId, String operatorName, LocalDateTime createdAt
    ) {}

    public record TransferCreate(
            @NotNull Long fromWarehouseId, @NotNull Long toWarehouseId, @NotNull LocalDate businessDate,
            LocalDate expectedArrivalDate,
            @Size(max = 500) String memo, @NotEmpty List<@Valid TransferItemCreate> items
    ) {
        public TransferCreate(Long fromWarehouseId, Long toWarehouseId, LocalDate businessDate,
                              String memo, List<TransferItemCreate> items) {
            this(fromWarehouseId, toWarehouseId, businessDate, null, memo, items);
        }
    }

    public record TransferItemCreate(
            @NotNull Long batchId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            List<Long> packageInstanceIds
    ) {}

    public record TransferRow(
            Long id, String orderNo, Long fromWarehouseId, String fromWarehouseName,
            Long toWarehouseId, String toWarehouseName, LocalDate businessDate, String status,
            LocalDate expectedArrivalDate,
            BigDecimal totalQuantity, BigDecimal totalCost, String memo,
            LocalDateTime dispatchedAt, LocalDateTime receivedAt, LocalDateTime createdAt
    ) {}

    public record TransferItem(
            Long id, Long productId, String sku, String productName, String spec,
            Long sourceBatchId, String sourceBatchNo, Long targetBatchId, String targetBatchNo,
            BigDecimal quantity, String baseUnit, BigDecimal unitCost, BigDecimal amountCost,
            List<Long> packageInstanceIds
    ) {}

    public record TransferDetail(TransferRow header, String cancelReason, String reversalReason,
                                 List<TransferItem> items, List<Event> events) {}

    public record StocktakeCreate(
            @NotNull Long warehouseId, @NotNull LocalDate businessDate, @Size(max = 500) String memo,
            @NotEmpty List<@Valid StocktakeItemCreate> items
    ) {}

    public record StocktakeItemCreate(
            @NotNull Long batchId, @NotNull @DecimalMin("0") @Digits(integer = 18, fraction = 6) BigDecimal countedQuantity,
            @Size(max = 500) String differenceReason
    ) {}

    public record StocktakeRow(
            Long id, String orderNo, Long warehouseId, String warehouseName, LocalDate businessDate,
            String status, BigDecimal totalGainQuantity, BigDecimal totalLossQuantity,
            BigDecimal totalCostDifference, String memo, LocalDateTime confirmedAt, LocalDateTime createdAt
    ) {}

    public record StocktakeItem(
            Long id, Long productId, String sku, String productName, String spec,
            Long batchId, String batchNo, BigDecimal systemQuantity, BigDecimal countedQuantity,
            BigDecimal differenceQuantity, String baseUnit, BigDecimal unitCost,
            BigDecimal costDifference, String differenceReason
    ) {}

    public record StocktakeDetail(StocktakeRow header, String cancelReason, String reversalReason,
                                  List<StocktakeItem> items, List<Event> events) {}

    public record PurchaseReturnCreate(
            @NotNull Long inboundId, @NotNull LocalDate businessDate,
            @NotNull @Size(min = 1, max = 500) String reason, @Size(max = 500) String memo,
            @NotEmpty List<@Valid PurchaseReturnItemCreate> items
    ) {}

    public record PurchaseReturnItemCreate(
            @NotNull Long inboundItemId, @NotNull Long batchId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            List<Long> packageInstanceIds
    ) {}

    public record PurchaseReturnRow(
            Long id, String orderNo, Long inboundId, String inboundNo, Long purchaseOrderId,
            String purchaseOrderNo, Long supplierId, String supplierName, Long warehouseId,
            String warehouseName, LocalDate businessDate, String status, BigDecimal totalQuantity,
            BigDecimal totalAmount, Boolean priceIncludesTax, String taxRateSummary, String taxRuleSnapshot,
            BigDecimal amountExcludingTax, BigDecimal taxAmount, BigDecimal taxRoundingDifference,
            BigDecimal payableReductionAmount, BigDecimal supplierCreditAmount,
            String reason, String memo, LocalDateTime confirmedAt, LocalDateTime createdAt
    ) {}

    public record PurchaseReturnItem(
            Long id, Long inboundItemId, Long productId, String sku, String productName, String spec,
            Long batchId, String batchNo, BigDecimal quantity, String baseUnit,
            BigDecimal inventoryUnitCost, BigDecimal inventoryAmountCost, BigDecimal payableUnitCost, BigDecimal amount,
            Boolean priceIncludesTax, BigDecimal taxRate, BigDecimal amountExcludingTax,
            BigDecimal taxAmount, BigDecimal taxRoundingDifference,
            List<Long> packageInstanceIds
    ) {}

    public record PurchaseReturnDetail(PurchaseReturnRow header, String cancelReason, String reversalReason,
                                       List<PurchaseReturnItem> items, List<Event> events) {}

    public record CostAdjustmentCreate(
            @NotNull LocalDate businessDate, @NotNull @Size(min = 1, max = 500) String reason,
            @Size(max = 500) String memo, @NotEmpty List<@Valid CostAdjustmentItemCreate> items
    ) {}

    public record CostAdjustmentItemCreate(
            @NotNull Long batchId, @NotNull @DecimalMin("0") @Digits(integer = 14, fraction = 4) BigDecimal newUnitCost
    ) {}

    public record CostAdjustmentRow(
            Long id, String adjustmentNo, LocalDate businessDate, String status,
            BigDecimal totalDifferenceAmount, String reason, String memo,
            LocalDateTime confirmedAt, LocalDateTime createdAt
    ) {}

    public record CostAdjustmentItem(
            Long id, Long productId, String sku, String productName, String spec,
            Long warehouseId, String warehouseName, Long batchId, String batchNo,
            BigDecimal quantitySnapshot, BigDecimal oldUnitCost, BigDecimal newUnitCost,
            BigDecimal oldAmountCost, BigDecimal newAmountCost, BigDecimal differenceAmount
    ) {}

    public record CostAdjustmentDetail(CostAdjustmentRow header, String cancelReason, String reversalReason,
                                       List<CostAdjustmentItem> items, List<Event> events) {}

    public record NonTradeCreate(
            @NotNull @Size(min = 1, max = 32) String businessType,
            @NotNull LocalDate businessDate,
            @Size(max = 128) String recipient,
            @Size(max = 128) String handlerName,
            @NotNull @Size(min = 1, max = 500) String reason,
            @Size(max = 500) String memo,
            @NotEmpty List<@Valid NonTradeItemCreate> items
    ) {
        public NonTradeCreate(Long batchId, String businessType, LocalDate businessDate,
                              BigDecimal quantity, String recipient, String reason, String memo,
                              List<Long> packageInstanceIds) {
            this(businessType, businessDate, recipient, "", reason, memo,
                    List.of(new NonTradeItemCreate(batchId, quantity, packageInstanceIds, List.of())));
        }

        public NonTradeCreate(Long batchId, String businessType, LocalDate businessDate,
                              BigDecimal quantity, String recipient, String reason, String memo,
                              List<Long> packageInstanceIds,
                              List<NonTradePackageAllocation> packageAllocations) {
            this(businessType, businessDate, recipient, "", reason, memo,
                    List.of(new NonTradeItemCreate(batchId, quantity, packageInstanceIds, packageAllocations)));
        }
    }

    public record NonTradeItemCreate(
            @NotNull Long batchId,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            List<Long> packageInstanceIds,
            @Valid List<NonTradePackageAllocation> packageAllocations
    ) {}

    public record NonTradePackageAllocation(
            @NotNull Long packageInstanceId,
            @NotNull @DecimalMin(value = "0", inclusive = false)
            @Digits(integer = 18, fraction = 6) BigDecimal quantity
    ) {}

    public record NonTradePackageAllocationDetail(
            Long packageInstanceId,
            BigDecimal quantity,
            String packageCode,
            String unitName,
            BigDecimal quantityBefore,
            BigDecimal quantityAfter
    ) {}

    public record NonTradeConfirm(
            @NotNull Long signedAttachmentId
    ) {}

    public record NonTradeRow(
            Long id, String documentNo, String businessType, String direction, LocalDate businessDate,
            Long productId, String sku, String productName, String spec,
            Long warehouseId, String warehouseName, Long batchId, String batchNo,
            BigDecimal quantity, String baseUnit, BigDecimal unitCost, BigDecimal amountCost,
            BigDecimal quantityBefore, BigDecimal amountBefore, String recipient, String reason,
            String memo, String status, String cancelReason, String reversalReason,
            Long signedAttachmentId, String signedAttachmentSha256,
            String handlerName, Integer itemCount, BigDecimal totalAmountCost,
            String issuedByName, LocalDateTime issuedAt,
            String createdByName, String confirmedByName, LocalDateTime confirmedAt,
            String reversedByName, LocalDateTime reversedAt, LocalDateTime createdAt
    ) {}

    public record NonTradeItem(
            Long id, Integer lineNo, Long productId, String sku, String productName, String spec,
            Long warehouseId, String warehouseName, Long batchId, String batchNo,
            BigDecimal quantity, String baseUnit, BigDecimal unitCost, BigDecimal amountCost,
            BigDecimal quantityBefore, BigDecimal quantityAfter, BigDecimal amountBefore,
            List<Long> packageInstanceIds,
            List<NonTradePackageAllocationDetail> packageAllocations
    ) {}

    public record NonTradeDetail(
            NonTradeRow header,
            List<NonTradeItem> items,
            List<Event> events
    ) {}
}
