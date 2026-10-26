package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PurchaseCreateRequest(
        @NotNull Long supplierId,
        @NotNull LocalDate businessDate,
        LocalDate expectedDate,
        Long expectedWarehouseId,
        String sourceType,
        String sourceNote,
        @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountRate,
        BigDecimal adjustmentAmount,
        String memo,
        @Valid @NotEmpty List<Item> items
) {
    public PurchaseCreateRequest(Long supplierId, LocalDate businessDate, LocalDate expectedDate,
                                 String sourceType, String sourceNote, BigDecimal discountRate,
                                 BigDecimal adjustmentAmount, String memo, List<Item> items) {
        this(supplierId, businessDate, expectedDate, null, sourceType, sourceNote, discountRate,
                adjustmentAmount, memo, items);
    }

    public record Item(
            Long itemId,
            @NotNull Long productId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            String quantityUnit,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity,
            Long expectedEffectiveRevisionId,
            String expectedPackagingSnapshotHash,
            String priceUnit,
            @Digits(integer = 18, fraction = 6) BigDecimal packageQuantity,
            String packageUnit,
            Long expectedSupplierOfferRevisionId,
            String expectedSupplierOfferSnapshotHash,
            Long packagingVariantRevisionId,
            boolean noCharge,
            @NotNull @DecimalMin("0.0000") BigDecimal unitPrice
    ) {
        public Item(Long itemId, Long productId, BigDecimal quantity, String quantityUnit,
                    BigDecimal actualBaseQuantity, Long expectedEffectiveRevisionId,
                    String expectedPackagingSnapshotHash, String priceUnit, String packageUnit,
                    BigDecimal unitPrice) {
            this(itemId, productId, quantity, quantityUnit, actualBaseQuantity,
                    expectedEffectiveRevisionId, expectedPackagingSnapshotHash,
                    priceUnit, null, packageUnit, null, null, null, false, unitPrice);
        }

        public Item(Long itemId, Long productId, BigDecimal quantity, String quantityUnit,
                    BigDecimal actualBaseQuantity, Long expectedEffectiveRevisionId,
                    String expectedPackagingSnapshotHash, String priceUnit, String packageUnit,
                    boolean noCharge, BigDecimal unitPrice) {
            this(itemId, productId, quantity, quantityUnit, actualBaseQuantity,
                    expectedEffectiveRevisionId, expectedPackagingSnapshotHash,
                    priceUnit, null, packageUnit, null, null, null, noCharge, unitPrice);
        }

        public Item(Long itemId, Long productId, BigDecimal quantity, String quantityUnit,
                    BigDecimal actualBaseQuantity, Long expectedEffectiveRevisionId,
                    String expectedPackagingSnapshotHash, String priceUnit, String packageUnit,
                    Long expectedSupplierOfferRevisionId, String expectedSupplierOfferSnapshotHash,
                    Long packagingVariantRevisionId, boolean noCharge, BigDecimal unitPrice) {
            this(itemId, productId, quantity, quantityUnit, actualBaseQuantity,
                    expectedEffectiveRevisionId, expectedPackagingSnapshotHash,
                    priceUnit, null, packageUnit, expectedSupplierOfferRevisionId,
                    expectedSupplierOfferSnapshotHash, packagingVariantRevisionId, noCharge, unitPrice);
        }
    }
}
