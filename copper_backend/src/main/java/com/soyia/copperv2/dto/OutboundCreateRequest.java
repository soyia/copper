package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OutboundCreateRequest(
        Long salesOrderId,
        @NotNull Long customerId,
        Long addressId,
        @NotNull Long warehouseId,
        @NotNull LocalDate businessDate,
        LocalDateTime actualShippedAt,
        String sourceChannel,
        String entryMode,
        String completenessStatus,
        String lateEntryReason,
        Boolean pricePending,
        String shipmentPaymentPolicy,
        BigDecimal shipmentControlAmount,
        Boolean confirmNow,
        Boolean allowOverShipment,
        String overShipmentReason,
        @NotBlank @Size(max = 64) String idempotencyKey,
        BigDecimal discountRate,
        BigDecimal adjustmentAmount,
        String memo,
        @Valid @NotEmpty List<Item> items
) {
    public record Item(
            Long salesOrderItemId,
            @NotNull Long batchId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            String quantityUnit,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity,
            Long expectedEffectiveRevisionId,
            String expectedPackagingSnapshotHash,
            @Digits(integer = 18, fraction = 6) BigDecimal packageQuantity,
            String packageUnit,
            String priceUnit,
            @NotNull @DecimalMin("0.0000") BigDecimal unitPrice,
            List<Long> packageInstanceIds
    ) {
    }
}
