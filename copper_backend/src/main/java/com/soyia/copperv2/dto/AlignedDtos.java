package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class AlignedDtos {
    public record ContactRow(
            Long id,
            Long partnerId,
            String name,
            String jobTitle,
            String department,
            Integer gender,
            String phone,
            String wechat,
            String email,
            String preferredMethod,
            Boolean firstChoice,
            LocalDate birthday,
            String memo,
            String status
    ) {
    }

    public record ContactCreate(
            @NotNull Long partnerId,
            @NotBlank String name,
            String jobTitle,
            String department,
            Integer gender,
            String phone,
            String wechat,
            String email,
            String preferredMethod,
            Boolean firstChoice,
            LocalDate birthday,
            String memo,
            String status
    ) {
    }

    public record AddressRow(
            Long id,
            Long partnerId,
            String name,
            String addressType,
            String provinceCode,
            String cityCode,
            String districtCode,
            String detail,
            String contactPerson,
            String phone,
            Boolean firstChoice,
            String memo,
            String status
    ) {
    }

    public record AddressCreate(
            @NotNull Long partnerId,
            String name,
            String addressType,
            String provinceCode,
            String cityCode,
            String districtCode,
            @NotBlank String detail,
            @NotBlank String contactPerson,
            String phone,
            Boolean firstChoice,
            String memo,
            String status
    ) {
    }

    public record FollowupRow(
            Long id,
            Long partnerId,
            Long contactId,
            String contactName,
            String contactPhone,
            String followupType,
            String methodType,
            java.time.LocalDateTime occurredAt,
            LocalDate nextFollowDate,
            String status,
            java.time.LocalDateTime completedAt,
            String memo
    ) {
    }

    public record FollowupCreate(
            @NotNull Long partnerId,
            Long contactId,
            String followupType,
            String methodType,
            @NotNull java.time.LocalDateTime occurredAt,
            LocalDate nextFollowDate,
            String status,
            String memo
    ) {
    }

    public record BankAccountRow(
            Long id,
            Long partnerId,
            String accountName,
            String bankName,
            String bankAccount,
            Boolean firstChoice,
            String status,
            String memo
    ) {
    }

    public record BankAccountCreate(
            @NotNull Long partnerId,
            @NotBlank String accountName,
            String bankName,
            @NotBlank String bankAccount,
            Boolean firstChoice,
            String status,
            String memo
    ) {
    }

    public record SpecialDateRow(
            Long id,
            Long partnerId,
            Long contactId,
            String contactName,
            String dateType,
            String title,
            LocalDate specialDate,
            Boolean repeatYearly,
            Integer remindDaysBefore,
            String memo,
            String status
    ) {
    }

    public record SpecialDateCreate(
            @NotNull Long partnerId,
            Long contactId,
            String dateType,
            @NotBlank String title,
            @NotNull LocalDate specialDate,
            Boolean repeatYearly,
            Integer remindDaysBefore,
            String memo,
            String status
    ) {
    }

    public record UnitCategoryRow(Long id, String name, String description, String status) {
    }

    public record UnitCategoryCreate(@NotBlank String name, String description, String status) {
    }

    public record UnitRow(
            Long id,
            Long unitCategoryId,
            String unitCategoryName,
            String name,
            String abbreviation,
            String description,
            String unitKind,
            List<String> aliases,
            String status
    ) {
    }

    public record UnitCreate(
            @NotNull Long unitCategoryId,
            @NotBlank String name,
            String abbreviation,
            String description,
            String unitKind,
            List<String> aliases,
            String status
    ) {
    }

    public record ProductSeriesRow(
            Long id,
            Long categoryId,
            String categoryName,
            String code,
            String name,
            String aliasNames,
            Long packageUnitCategoryId,
            Long priceUnitCategoryId,
            Integer identitySchemaVersion,
            String status
    ) {
    }

    public record ProductSeriesCreate(
            Long categoryId,
            @NotBlank
            @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]*$", message = "系列编码只能使用大写字母、数字、- 和 _")
            String code,
            @NotBlank String name,
            String aliasNames,
            Long packageUnitCategoryId,
            Long priceUnitCategoryId,
            String status
    ) {
    }

    public record SalesOrderRow(
            Long id,
            String orderNo,
            String title,
            String customerName,
            LocalDate businessDate,
            LocalDate deliveryPlanDate,
            Long fulfillmentWarehouseId,
            String fulfillmentWarehouseName,
            BigDecimal originalAmount,
            BigDecimal discountAmount,
            BigDecimal adjustmentAmount,
            BigDecimal totalAmount,
            BigDecimal receivableAmount,
            BigDecimal receivedAmount,
            BigDecimal adjustedAmount,
            BigDecimal cashReceivedAmount,
            BigDecimal outstandingAmount,
            String paymentStatus,
            String paymentTerm,
            String shipmentPaymentPolicy,
            BigDecimal requiredDepositAmount,
            BigDecimal reservedPrepaymentAmount,
            BigDecimal prepaymentShortfall,
            BigDecimal shipmentFundedAmount,
            BigDecimal shipmentRequiredAmount,
            BigDecimal shipmentPaymentShortfall,
            String status
    ) {
    }

    public record SalesOrderCreate(
            String title,
            @NotNull Long customerId,
            @NotNull Long addressId,
            @NotNull LocalDate businessDate,
            LocalDate deliveryPlanDate,
            Long fulfillmentWarehouseId,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountRate,
            BigDecimal adjustmentAmount,
            String paymentTerm,
            String shipmentPaymentPolicy,
            String depositMode,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal depositRate,
            @DecimalMin("0.00") BigDecimal requiredDepositAmount,
            String memo,
            @Valid @NotEmpty List<Item> items
    ) {
        public SalesOrderCreate(String title, Long customerId, Long addressId, LocalDate businessDate,
                                LocalDate deliveryPlanDate, BigDecimal discountRate, BigDecimal adjustmentAmount,
                                String paymentTerm, String shipmentPaymentPolicy, String depositMode,
                                BigDecimal depositRate, BigDecimal requiredDepositAmount, String memo,
                                List<Item> items) {
            this(title, customerId, addressId, businessDate, deliveryPlanDate, null, discountRate,
                    adjustmentAmount, paymentTerm, shipmentPaymentPolicy, depositMode, depositRate,
                    requiredDepositAmount, memo, items);
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
                Boolean noCharge,
                @NotNull @DecimalMin("0.0000") BigDecimal unitPrice
        ) {
            public Item(Long itemId, Long productId, BigDecimal quantity, String quantityUnit,
                        BigDecimal actualBaseQuantity, Long expectedEffectiveRevisionId,
                        String expectedPackagingSnapshotHash, String priceUnit, String packageUnit,
                        Boolean noCharge, BigDecimal unitPrice) {
                this(itemId, productId, quantity, quantityUnit, actualBaseQuantity,
                        expectedEffectiveRevisionId, expectedPackagingSnapshotHash,
                        priceUnit, null, packageUnit, noCharge, unitPrice);
            }
        }
    }
}
