package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record InboundCreateRequest(
        Long purchaseOrderId,
        @NotNull Long supplierId,
        @NotNull Long warehouseId,
        String sourceType,
        String sourceNote,
        @NotNull LocalDate businessDate,
        LocalDate sendDate,
        String sendNumber,
        BigDecimal discountRate,
        BigDecimal adjustmentAmount,
        String memo,
        @Valid @NotEmpty List<Item> items
) {
        public record Item(
            @NotNull Long productId,
            Long purchaseOrderItemId,
            String batchNo,
            String serialNumber,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            String quantityUnit,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity,
            Long expectedEffectiveRevisionId,
            String expectedPackagingSnapshotHash,
            @Digits(integer = 18, fraction = 6) BigDecimal packageQuantity,
            String priceUnit,
            String packageUnit,
            String packageBarcode,
            @NotNull @DecimalMin("0.0001") BigDecimal unitCost,
            @Valid List<PackagingDtos.PackageInstanceInput> packageInstances,
            LocalDate productionDate
    ) {
    }
}
