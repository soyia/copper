package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class OrderDtos {
    public record InboundRow(
            Long id,
            String orderNo,
            Long purchaseOrderId,
            String purchaseOrderNo,
            String supplierName,
            String warehouseName,
            String sourceType,
            LocalDate businessDate,
            String sendNumber,
            BigDecimal totalQuantity,
            String quantitySummary,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String status
    ) {
    }

    public record InboundDetail(
            Long id,
            String orderNo,
            Long purchaseOrderId,
            String purchaseOrderNo,
            Long supplierId,
            String supplierName,
            Long warehouseId,
            String warehouseName,
            String sourceType,
            String sourceNote,
            LocalDate businessDate,
            LocalDate sendDate,
            String sendNumber,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal adjustmentAmount,
            BigDecimal totalQuantity,
            BigDecimal totalAmount,
            BigDecimal payableAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String status,
            String rejectionReason,
            LocalDateTime submittedAt,
            LocalDateTime withdrawnAt,
            LocalDateTime rejectedAt,
            LocalDateTime auditedAt,
            String memo,
            List<InboundItemDetail> items,
            List<InboundNonTradeRecord> nonTradeRecords
    ) {
    }

    public record InboundItemDetail(
            Long id,
            Long productId,
            String sku,
            String productName,
            String grade,
            String temper,
            String standard,
            String spec,
            String unit,
            Long inventoryBatchId,
            String batchNo,
            String serialNumber,
            BigDecimal quantity,
            BigDecimal currentBookQuantity,
            BigDecimal reservedQuantity,
            BigDecimal currentFreeQuantity,
            BigDecimal nonTradeIssuedQuantity,
            BigDecimal packageQuantity,
            String priceUnit,
            String packageUnit,
            String packageBarcode,
            BigDecimal unitCost,
            BigDecimal amount,
            LocalDate productionDate,
            String shelfLifeMode,
            Integer shelfLifeValue,
            String shelfLifeUnit,
            LocalDate expirationDate,
            Long purchaseOrderItemId,
            BigDecimal requestedQuantity,
            String requestedUnit,
            String baseUnit,
            BigDecimal pricingQuantity,
            String packagingPlanJson,
            List<PackagingDtos.PackageInstanceInput> packageInstances
    ) {
        public InboundItemDetail withPackageInstances(List<PackagingDtos.PackageInstanceInput> values) {
            return new InboundItemDetail(id, productId, sku, productName, grade, temper, standard, spec, unit,
                    inventoryBatchId, batchNo, serialNumber, quantity, currentBookQuantity, reservedQuantity,
                    currentFreeQuantity, nonTradeIssuedQuantity, packageQuantity, priceUnit, packageUnit,
                    packageBarcode, unitCost, amount, productionDate, shelfLifeMode, shelfLifeValue, shelfLifeUnit,
                    expirationDate, purchaseOrderItemId, requestedQuantity, requestedUnit, baseUnit,
                    pricingQuantity, packagingPlanJson, values);
        }
    }

    public record InboundNonTradeRecord(
            Long id,
            String documentNo,
            String businessType,
            LocalDate businessDate,
            Long productId,
            Long batchId,
            String batchNo,
            BigDecimal quantity,
            String baseUnit,
            String recipient,
            String handlerName,
            String status,
            LocalDateTime issuedAt
    ) {
    }

    public record InboundAttachment(
            Long id,
            Long inboundId,
            String fileName,
            String contentType,
            Long fileSize,
            String sha256,
            LocalDateTime uploadedAt
    ) {
    }

    public record InboundAuditLog(
            Long id,
            Long inboundId,
            String action,
            String reason,
            Long operatorId,
            String operatorName,
            Long snapshotId,
            Integer reviewRound,
            LocalDateTime createdAt
    ) {
    }

    public record InboundAuditLogDetail(
            InboundAuditLog auditLog,
            InboundReviewSnapshot snapshot,
            InboundReviewSnapshot previousSnapshot
    ) {
    }

    public record InboundReviewSnapshot(
            Long id,
            Long inboundId,
            Integer roundNo,
            String orderNo,
            Long purchaseOrderId,
            String purchaseOrderNo,
            Long supplierId,
            String supplierName,
            Long warehouseId,
            String warehouseName,
            String sourceType,
            String sourceNote,
            LocalDate businessDate,
            LocalDate sendDate,
            String sendNumber,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal adjustmentAmount,
            BigDecimal totalQuantity,
            String quantitySummary,
            BigDecimal totalAmount,
            String memo,
            String snapshotHash,
            String captureSource,
            String submittedByName,
            LocalDateTime createdAt,
            List<InboundReviewSnapshotItem> items,
            List<InboundReviewSnapshotAttachment> attachments
    ) {
    }

    public record InboundReviewSnapshotItem(
            Long id,
            Integer lineNo,
            Long productId,
            String sku,
            String productName,
            String spec,
            String batchNo,
            String serialNumber,
            BigDecimal requestedQuantity,
            String requestedUnit,
            BigDecimal quantity,
            String baseUnit,
            BigDecimal packageQuantity,
            String packageUnit,
            String priceUnit,
            BigDecimal pricingQuantity,
            String packageBarcode,
            BigDecimal unitCost,
            BigDecimal amount,
            LocalDate productionDate,
            String shelfLifeMode,
            Integer shelfLifeValue,
            String shelfLifeUnit,
            LocalDate expirationDate,
            String packagingPlanJson
    ) {
    }

    public record InboundReviewSnapshotAttachment(
            Long id,
            Long originalAttachmentId,
            String fileName,
            String contentType,
            Long fileSize,
            String sha256,
            LocalDateTime originalUploadedAt
    ) {
    }

    public record OutboundRow(
            Long id,
            String orderNo,
            Long salesOrderId,
            String salesOrderNo,
            String customerName,
            String warehouseName,
            LocalDate businessDate,
            LocalDateTime actualShippedAt,
            String sourceType,
            String sourceChannel,
            String entryMode,
            String completenessStatus,
            String settlementStatus,
            BigDecimal totalQuantity,
            BigDecimal packageQuantity,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String status
    ) {
    }

    public record OutboundDetail(
            Long id,
            String orderNo,
            Long salesOrderId,
            String salesOrderNo,
            Long customerId,
            String customerName,
            Long addressId,
            String addressSnapshot,
            Long warehouseId,
            String warehouseName,
            LocalDate businessDate,
            LocalDateTime actualShippedAt,
            String sourceType,
            String sourceChannel,
            String entryMode,
            String completenessStatus,
            String settlementStatus,
            String lateEntryReason,
            Boolean allowOverShipment,
            String overShipmentReason,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal adjustmentAmount,
            BigDecimal totalQuantity,
            BigDecimal packageQuantity,
            BigDecimal totalAmount,
            BigDecimal receivableAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String shipmentPaymentPolicy,
            BigDecimal shipmentControlAmount,
            BigDecimal shipmentRequiredAmount,
            BigDecimal shipmentFundedAmount,
            BigDecimal shipmentPaymentShortfall,
            BigDecimal customerAdvanceAvailable,
            String status,
            String memo,
            String cancelReason,
            LocalDateTime cancelledAt,
            LocalDateTime confirmedAt,
            LocalDateTime settledAt,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt,
            List<OutboundItemDetail> items
    ) {
    }

    public record OutboundItemDetail(
            Long id,
            Long productId,
            String sku,
            String productName,
            String grade,
            String temper,
            String standard,
            String spec,
            String unit,
            Long batchId,
            String batchNo,
            BigDecimal quantity,
            BigDecimal packageQuantity,
            String priceUnit,
            BigDecimal unitPrice,
            BigDecimal amount,
            Long salesOrderItemId,
            BigDecimal requestedQuantity,
            String requestedUnit,
            String baseUnit,
            BigDecimal pricingQuantity,
            String packageUnit,
            String packagingPlanJson,
            String selectedPackageInstanceIdsJson,
            BigDecimal returnedQuantity
    ) {
    }

    public record PaymentRow(
            Long id,
            String paymentNo,
            Long customerId,
            String customerName,
            Long salesOrderId,
            String salesOrderNo,
            Long outboundId,
            String outboundNo,
            BigDecimal amount,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount,
            Integer allocationCount,
            String fundNature,
            String allocationMode,
            String paymentMethod,
            String accountType,
            Long businessBankAccountId,
            String businessBankAccountName,
            LocalDate receivedDate,
            String verificationStatus,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt
    ) {
    }

    public record PurchaseOrderRow(
            Long id,
            String orderNo,
            String supplierName,
            LocalDate businessDate,
            LocalDate expectedDate,
            Long expectedWarehouseId,
            String expectedWarehouseName,
            String sourceType,
            BigDecimal totalQuantity,
            BigDecimal receivedQuantity,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal adjustmentAmount,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String status
    ) {
    }

    public record PurchaseOrderDetail(
            Long id,
            String orderNo,
            Long supplierId,
            String supplierName,
            LocalDate businessDate,
            LocalDate expectedDate,
            Long expectedWarehouseId,
            String expectedWarehouseName,
            String sourceType,
            String sourceNote,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal adjustmentAmount,
            Boolean priceIncludesTax,
            String taxRateSummary,
            String taxRuleSnapshot,
            BigDecimal amountExcludingTax,
            BigDecimal taxAmount,
            BigDecimal taxRoundingDifference,
            BigDecimal totalQuantity,
            BigDecimal receivedQuantity,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            String paymentStatus,
            String status,
            String memo,
            String cancelReason,
            LocalDateTime cancelledAt,
            List<PurchaseOrderItemDetail> items
    ) {
    }

    public record PurchaseOrderItemDetail(
            Long id,
            Long productId,
            String sku,
            String productName,
            String grade,
            String temper,
            String standard,
            String spec,
            String unit,
            BigDecimal quantity,
            BigDecimal receivedQuantity,
            String priceUnit,
            BigDecimal packageQuantity,
            String packageUnit,
            String priceSourceCode,
            String priceSourceName,
            Long priceRuleVersionId,
            String priceSnapshotJson,
            boolean noCharge,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal taxRate,
            BigDecimal unitPriceExcludingTax,
            BigDecimal amountExcludingTax,
            BigDecimal taxAmount,
            BigDecimal settlementTotalAmount,
            BigDecimal taxRoundingDifference,
            String status,
            BigDecimal requestedQuantity,
            String requestedUnit,
            String baseUnit,
            BigDecimal pricingQuantity,
            String packagingPlanJson,
            BigDecimal receivedRequestedQuantity
    ) {
    }

    public record SupplierPaymentRow(
            Long id,
            String paymentNo,
            Long supplierId,
            String supplierName,
            Long purchaseOrderId,
            String purchaseOrderNo,
            Long inboundId,
            String inboundNo,
            BigDecimal amount,
            BigDecimal allocatedAmount,
            BigDecimal unallocatedAmount,
            Integer allocationCount,
            String fundNature,
            String allocationMode,
            String paymentMethod,
            String accountType,
            Long businessBankAccountId,
            String businessBankAccountName,
            LocalDate paidDate,
            String verificationStatus,
            String reversalNo,
            String reversalReason,
            LocalDateTime reversedAt
    ) {
    }

    public record PayableRow(
            String sourceType,
            Long sourceId,
            String sourceNo,
            Long supplierId,
            String supplierName,
            LocalDate businessDate,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Integer settlementDays,
            LocalDate dueDate,
            Integer overdueDays,
            String paymentStatus,
            String status
    ) {
    }

    public record ReceivableRow(
            String sourceType,
            Long sourceId,
            String sourceNo,
            Long customerId,
            String customerName,
            LocalDate businessDate,
            BigDecimal totalAmount,
            BigDecimal paidAmount,
            BigDecimal unpaidAmount,
            Integer settlementDays,
            LocalDate dueDate,
            Integer overdueDays,
            String paymentStatus,
            String status
    ) {
    }

    public record PartnerAccountRow(
            Long id,
            Long partnerId,
            String partnerName,
            String partnerType,
            String accountType,
            BigDecimal balance,
            BigDecimal frozenAmount,
            BigDecimal creditLimit
    ) {
    }

    public record PartnerAccountLogRow(
            Long id,
            Long partnerId,
            String partnerName,
            String accountType,
            String sourceType,
            Long sourceId,
            String businessType,
            Long businessId,
            String businessNo,
            String businessSummary,
            BigDecimal amount,
            BigDecimal amountBefore,
            BigDecimal amountAfter,
            java.time.LocalDateTime createdAt
    ) {
    }

    public record SalesOrderDetail(
            Long id,
            String orderNo,
            String title,
            Long customerId,
            String customerName,
            Long addressId,
            String addressSnapshot,
            LocalDate businessDate,
            LocalDate deliveryPlanDate,
            Long fulfillmentWarehouseId,
            String fulfillmentWarehouseName,
            BigDecimal originalAmount,
            BigDecimal discountRate,
            BigDecimal discountAmount,
            BigDecimal adjustmentAmount,
            Boolean priceIncludesTax,
            String taxRateSummary,
            String taxRuleSnapshot,
            BigDecimal amountExcludingTax,
            BigDecimal taxAmount,
            BigDecimal taxRoundingDifference,
            BigDecimal totalAmount,
            BigDecimal receivableAmount,
            BigDecimal receivedAmount,
            BigDecimal adjustedAmount,
            BigDecimal cashReceivedAmount,
            BigDecimal outstandingAmount,
            BigDecimal roundedAmount,
            String paymentStatus,
            String paymentTerm,
            String shipmentPaymentPolicy,
            String depositMode,
            BigDecimal depositRate,
            BigDecimal requiredDepositAmount,
            BigDecimal reservedPrepaymentAmount,
            BigDecimal prepaymentShortfall,
            BigDecimal customerAdvanceBalance,
            BigDecimal customerAdvanceAvailable,
            BigDecimal shipmentFundedAmount,
            BigDecimal shipmentRequiredAmount,
            BigDecimal shipmentPaymentShortfall,
            String status,
            String memo,
            String cancelReason,
            LocalDateTime cancelledAt,
            List<SalesOrderItemDetail> items
    ) {
    }

    public record SalesOrderPrepaymentSummary(
            Long salesOrderId,
            String orderNo,
            Long customerId,
            String customerName,
            String orderStatus,
            String paymentTerm,
            BigDecimal orderAmount,
            BigDecimal requiredAmount,
            BigDecimal reservedAmount,
            BigDecimal shortfallAmount,
            BigDecimal customerAdvanceBalance,
            BigDecimal customerAdvanceReserved,
            BigDecimal customerAdvanceAvailable,
            List<SalesOrderAdvanceAllocation> allocations
    ) {
    }

    public record SalesOrderAdvanceAllocation(
            Long id,
            Long salesOrderId,
            Long sourcePaymentId,
            String sourcePaymentNo,
            BigDecimal amount,
            String status,
            Long appliedPaymentId,
            String memo,
            java.time.LocalDateTime createdAt
    ) {
    }

    public record CustomerAdvanceRow(
            Long customerId,
            String customerName,
            BigDecimal balance,
            BigDecimal reservedAmount,
            BigDecimal availableAmount,
            java.time.LocalDateTime updatedAt
    ) {
    }

    public record CustomerAdvanceRefundRow(
            Long id,
            String refundNo,
            Long customerId,
            String customerName,
            BigDecimal amount,
            String refundMethod,
            Long businessBankAccountId,
            String businessBankAccountName,
            LocalDate refundedDate,
            String status,
            String memo,
            String reversalNo,
            String reversalReason,
            java.time.LocalDateTime reversedAt,
            java.time.LocalDateTime createdAt
    ) {
    }

    public record SalesOrderItemDetail(
            Long id,
            Long productId,
            String sku,
            String productName,
            String grade,
            String temper,
            String standard,
            String spec,
            String unit,
            BigDecimal quantity,
            String priceUnit,
            BigDecimal packageQuantity,
            String packageUnit,
            String priceSourceCode,
            String priceSourceName,
            Long priceRuleVersionId,
            String priceSnapshotJson,
            Boolean noCharge,
            BigDecimal unitPrice,
            BigDecimal amount,
            BigDecimal taxRate,
            BigDecimal unitPriceExcludingTax,
            BigDecimal amountExcludingTax,
            BigDecimal taxAmount,
            BigDecimal settlementTotalAmount,
            BigDecimal taxRoundingDifference,
            String status,
            BigDecimal requestedQuantity,
            String requestedUnit,
            String baseUnit,
            BigDecimal pricingQuantity,
            String packagingPlanJson,
            BigDecimal shippedQuantity,
            BigDecimal shippedRequestedQuantity
    ) {
    }
}
