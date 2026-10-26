package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class AtpDtos {
    private AtpDtos() {}

    public record Summary(
            Long productId, String sku, String productName, String baseUnit,
            Long warehouseId, String warehouseName, LocalDate asOfDate,
            BigDecimal onHandQuantity, BigDecimal outboundDraftQuantity,
            BigDecimal salesCommitmentQuantity, BigDecimal linkedDraftCoveredQuantity,
            BigDecimal purchaseIncomingQuantity, BigDecimal transferIncomingQuantity,
            BigDecimal atpQuantity, BigDecimal unallocatedSalesCommitmentQuantity,
            BigDecimal unallocatedPurchaseIncomingQuantity, String status,
            LocalDateTime calculatedAt
    ) {}

    public record TimelineRow(
            LocalDate eventDate, String eventType, String sourceType, Long sourceId,
            String sourceNo, Long productId, Long warehouseId, String direction,
            BigDecimal quantity, BigDecimal coveredQuantity, BigDecimal netQuantity,
            String status
    ) {}

    public record Issue(String code, String message, LocalDate shortageDate, BigDecimal shortageQuantity) {}

    public record OrderItemEvaluation(
            Long salesOrderItemId, Long productId, String sku, String productName, String baseUnit,
            BigDecimal commitmentQuantity, BigDecimal onHandQuantity, BigDecimal outboundDraftQuantity,
            BigDecimal salesCommitmentQuantity, BigDecimal linkedDraftCoveredQuantity,
            BigDecimal purchaseIncomingQuantity, BigDecimal transferIncomingQuantity,
            BigDecimal atpBeforeQuantity, BigDecimal atpAfterQuantity,
            LocalDate earliestShortageDate, BigDecimal shortageQuantity
    ) {}

    public record OrderEvaluation(
            Long salesOrderId, String salesOrderNo, Long warehouseId, String warehouseName,
            LocalDate promiseDate, String calculationVersion, boolean canPromise,
            List<Issue> issues, List<OrderItemEvaluation> items, LocalDateTime calculatedAt
    ) {}

    public record Snapshot(
            Long id, String snapshotNo, String action, String decision,
            Long salesOrderId, Long salesOrderItemId, String salesOrderNo, Integer revisionNo,
            Long productId, String sku, String productName, Long warehouseId, String warehouseName,
            LocalDate promiseDate, BigDecimal commitmentQuantity,
            BigDecimal onHandQuantity, BigDecimal outboundDraftQuantity,
            BigDecimal salesCommitmentQuantity, BigDecimal linkedDraftCoveredQuantity,
            BigDecimal purchaseIncomingQuantity, BigDecimal transferIncomingQuantity,
            BigDecimal atpBeforeQuantity, BigDecimal atpAfterQuantity,
            LocalDate earliestShortageDate, String calculationVersion,
            Long operatorId, String operatorName, LocalDateTime createdAt
    ) {}
}
