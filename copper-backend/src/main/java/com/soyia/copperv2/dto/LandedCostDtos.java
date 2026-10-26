package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class LandedCostDtos {
    private LandedCostDtos() {}

    public record SourceOption(
            Long id, String documentNo, Long partnerId, String partnerName, LocalDate businessDate,
            String summary, BigDecimal amountExcludingTax, BigDecimal taxAmount, BigDecimal totalAmount,
            BigDecimal capitalizableAmount, String inputTaxTreatment, String status, boolean allocated
    ) {}

    public record InboundOption(
            Long inboundId, String inboundNo, LocalDate inboundDate, Long supplierId, String supplierName,
            Long inboundItemId, Long batchId, String batchNo, Long productId, String sku,
            String productName, String spec, Long warehouseId, String warehouseName,
            BigDecimal originalQuantity, BigDecimal currentQuantity, String baseUnit,
            BigDecimal amountExcludingTax, BigDecimal suggestedWeight
    ) {}

    public record Options(List<SourceOption> sources, List<InboundOption> inboundItems) {}

    public record AllocationCreate(
            @NotNull Long treasuryDocumentId,
            @NotNull LocalDate businessDate,
            @NotNull @Size(min = 1, max = 16) String allocationMethod,
            @Size(max = 500) String memo,
            @NotEmpty List<@Valid ItemCreate> items
    ) {}

    public record ItemCreate(
            @NotNull Long inboundItemId,
            @DecimalMin("0") BigDecimal basisValue,
            @DecimalMin("0") BigDecimal allocatedAmount
    ) {}

    public record AllocationRow(
            Long id, String allocationNo, Long treasuryDocumentId, String treasuryDocumentNo,
            String feePartnerName, LocalDate businessDate, String allocationMethod, String status,
            BigDecimal feeAmount, BigDecimal allocatedAmount, BigDecimal capitalizedAmount,
            BigDecimal expensedAmount, String memo, LocalDateTime confirmedAt, LocalDateTime createdAt
    ) {}

    public record AllocationItem(
            Long id, Long inboundId, String inboundNo, Long inboundItemId, Long originalBatchId,
            String batchNo, Long productId, String sku, String productName, String spec,
            Long warehouseId, String warehouseName, BigDecimal originalQuantity,
            BigDecimal remainingQuantitySnapshot, String baseUnit, BigDecimal basisValue,
            BigDecimal allocatedAmount, BigDecimal landedUnitAmount,
            BigDecimal capitalizedAmount, BigDecimal expensedAmount
    ) {}

    public record BatchAllocation(
            Long id, Long allocationItemId, Long batchId, String batchNo, String warehouseName,
            BigDecimal quantitySnapshot, BigDecimal oldUnitCost, BigDecimal allocatedAmount,
            BigDecimal newUnitCost, BigDecimal oldAmountCost, BigDecimal newAmountCost
    ) {}

    public record Event(
            Long id, String action, String fromStatus, String toStatus, String reason,
            String operatorName, LocalDateTime createdAt
    ) {}

    public record AllocationDetail(
            AllocationRow header, String cancelReason, String reversalReason,
            Long postingBatchId, Long accountingVoucherId, String accountingVoucherNo,
            List<AllocationItem> items, List<BatchAllocation> batches, List<Event> events
    ) {}

    public record Action(@NotNull @Size(min = 4, max = 500) String reason, LocalDate businessDate) {}
}
