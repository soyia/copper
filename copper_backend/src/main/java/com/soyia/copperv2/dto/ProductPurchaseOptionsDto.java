package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ProductPurchaseOptionsDto(
        Long productId,
        String defaultPriceUnit,
        List<UnitOption> priceUnits,
        List<UnitOption> packageUnits,
        PriceSuggestion priceSuggestion,
        SupplierOfferContext supplierOffer
) {
    public record UnitOption(
            String name,
            Long unitId,
            String unitName,
            BigDecimal exchangeRate,
            Boolean primaryUnit
    ) {
    }

    public record PriceSuggestion(
            BigDecimal unitPrice,
            String priceUnit,
            String source,
            String sourceName,
            String explanation,
            Long ruleVersionId,
            Boolean priceIncludesTax,
            BigDecimal taxRate,
            List<PriceCenterDtos.Component> components,
            List<String> warnings,
            String snapshotJson
    ) {
    }

    public record SupplierOfferContext(
            Long supplierOfferId,
            Long supplierOfferRevisionId,
            Integer supplierOfferRevisionNo,
            Long supplierId,
            String purchaseUnit,
            String priceUnit,
            String currencyCode,
            BigDecimal quotedUnitPrice,
            Boolean priceIncludesTax,
            BigDecimal taxRate,
            BigDecimal minimumOrderQuantity,
            BigDecimal orderMultiple,
            Integer leadTimeDays,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long packagingVariantId,
            Long packagingVariantRevisionId,
            Integer packagingVariantRevisionNo,
            String packagingVariantName,
            String packageUnit,
            String conversionMode,
            BigDecimal fixedBaseQuantity,
            BigDecimal referenceBaseQuantity,
            String snapshotJson,
            String snapshotHash
    ) {
    }
}
