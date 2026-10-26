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

public final class DirectTradeDtos {
    private DirectTradeDtos() {}

    public record Create(
            @NotNull Long customerId,
            @NotNull Long supplierId,
            @NotNull Long addressId,
            @NotNull LocalDate businessDate,
            LocalDate expectedDeliveryDate,
            @Size(max = 500) String memo,
            @NotEmpty @Valid List<ItemCreate> items
    ) {}

    public record ItemCreate(
            @NotNull Long productId,
            @NotNull @DecimalMin("0.000001") BigDecimal quantity,
            String quantityUnit,
            @DecimalMin("0.000001") BigDecimal actualBaseQuantity,
            Long expectedEffectiveRevisionId,
            String expectedPackagingSnapshotHash,
            String priceUnit,
            String packageUnit,
            @NotNull @DecimalMin("0.00000000") BigDecimal purchaseUnitPrice,
            @NotNull @DecimalMin("0.00000000") BigDecimal saleUnitPrice
    ) {}

    public record Confirm(
            @Size(max = 500) String riskOverrideReason,
            Long riskApprovalId
    ) {}

    public record QuantityInput(
            @NotNull Long itemId,
            @NotNull @DecimalMin("0.000001") BigDecimal quantity
    ) {}

    public record Dispatch(
            @NotNull LocalDate businessDate,
            @Size(max = 128) String carrierName,
            @Size(max = 128) String trackingNo,
            @Size(max = 128) String handlerName,
            @Valid List<QuantityInput> items
    ) {}

    public record AcceptInput(
            @NotNull Long deliveryItemId,
            @NotNull @DecimalMin("0.000000") BigDecimal acceptedQuantity
    ) {}

    public record Accept(
            @NotNull LocalDate acceptedDate,
            @Size(max = 128) String recipientName,
            @Size(max = 500) String differenceReason,
            @Valid List<AcceptInput> items
    ) {}

    public record ReturnCreate(
            @NotNull LocalDate businessDate,
            @NotBlank @Size(min = 2, max = 500) String reason,
            @NotEmpty @Valid List<QuantityInput> items
    ) {}

    public record Action(
            @NotBlank @Size(min = 2, max = 500) String reason
    ) {}

    public record Row(
            Long id, String tradeNo, Long customerId, String customerName,
            Long supplierId, String supplierName, LocalDate businessDate,
            LocalDate expectedDeliveryDate, String status, BigDecimal totalQuantity,
            String baseUnitSummary, BigDecimal saleOrderAmount, BigDecimal purchaseOrderAmount,
            BigDecimal saleReceivableAmount, BigDecimal purchasePayableAmount,
            BigDecimal receivedAmount, BigDecimal paidAmount, BigDecimal grossProfitAmount,
            String paymentStatus, String supplierPaymentStatus, LocalDateTime updatedAt
    ) {}

    public record Item(
            Long id, Integer lineNo, Long productId, String productSku, String productName,
            String productSpec, Long salesOrderItemId, Long purchaseOrderItemId,
            BigDecimal requestedQuantity, String requestedUnit, BigDecimal quantity,
            String baseUnit, String priceUnit, BigDecimal pricingQuantity, String packageUnit,
            String packagingPlanJson, Long packagingEffectiveRevisionId, String packagingSnapshotHash,
            BigDecimal saleUnitPrice, BigDecimal purchaseUnitPrice,
            BigDecimal saleAmount, BigDecimal purchaseAmount,
            BigDecimal deliveredQuantity, BigDecimal returnedQuantity,
            BigDecimal remainingQuantity, BigDecimal recognizedSaleAmount,
            BigDecimal recognizedPurchaseAmount
    ) {}

    public record DeliveryItem(
            Long id, Long directTradeItemId, Long productId, String productSku,
            String productName, String productSpec, BigDecimal dispatchedQuantity,
            BigDecimal acceptedQuantity, String baseUnit, BigDecimal saleAmount,
            BigDecimal purchaseAmount
    ) {}

    public record Delivery(
            Long id, String deliveryNo, Integer sequenceNo, LocalDate businessDate,
            String carrierName, String trackingNo, String handlerName, String status,
            BigDecimal dispatchedQuantity, BigDecimal acceptedQuantity,
            BigDecimal saleAmount, BigDecimal purchaseAmount,
            String recipientName, String differenceReason,
            String dispatchedByName, String acceptedByName,
            LocalDateTime dispatchedAt, LocalDateTime acceptedAt,
            List<DeliveryItem> items
    ) {}

    public record ReturnRow(
            Long id, String returnNo, LocalDate businessDate, BigDecimal totalQuantity,
            BigDecimal saleReductionAmount, BigDecimal purchaseReductionAmount,
            String reason, String status, String createdByName, LocalDateTime confirmedAt
    ) {}

    public record Event(
            Long id, String eventType, String refType, Long refId,
            String fromStatus, String toStatus, String reason,
            String operatorName, LocalDateTime createdAt
    ) {}

    public record Detail(
            Row header, Long addressId, String addressSnapshot,
            Long salesOrderId, String salesOrderNo, Long purchaseOrderId, String purchaseOrderNo,
            Boolean salePriceIncludesTax, String saleTaxRateSummary,
            Boolean purchasePriceIncludesTax, String purchaseTaxRateSummary,
            String memo, String cancelReason, String createdByName,
            String confirmedByName, LocalDateTime confirmedAt,
            List<Item> items, List<Delivery> deliveries,
            List<ReturnRow> returns, List<Event> events
    ) {}
}
