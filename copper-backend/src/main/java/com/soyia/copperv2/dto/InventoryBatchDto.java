package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record InventoryBatchDto(
        Long id,
        Long productId,
        String sku,
        String productName,
        String grade,
        String spec,
        Long warehouseId,
        String warehouseName,
        String batchNo,
        Long sourceInboundId,
        String sourceInboundNo,
        BigDecimal quantityIn,
        BigDecimal quantityAvailable,
        BigDecimal quantityReserved,
        BigDecimal quantityFree,
        BigDecimal quantityInTransit,
        String baseUnit,
        Long packagingEffectiveRevisionId,
        String packagingProfileSnapshotJson,
        String packagingSnapshotHash,
        String packagingTrackingMode,
        String packageBarcode,
        BigDecimal unitCost,
        BigDecimal amountCost,
        String status,
        LocalDate receivedAt,
        LocalDate productionDate,
        String shelfLifeMode,
        Integer shelfLifeValue,
        String shelfLifeUnit,
        LocalDate expirationDate
) {
}
