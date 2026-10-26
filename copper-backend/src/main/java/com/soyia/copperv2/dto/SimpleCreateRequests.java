package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class SimpleCreateRequests {
    public record PartnerCreate(
            String type,
            Integer typeMask,
            String entityType,
            @NotBlank String name,
            String aliasName,
            String creditCode,
            String contactPerson,
            String primaryContactRole,
            String phone,
            String wechat,
            String email,
            String address,
            BigDecimal creditLimit,
            Boolean topFixed,
            LocalDate cooperationStartDate,
            LocalDate establishmentDate,
            String source,
            String tags,
            String memo,
            String status,
            String disabledReason,
            String customerStatus,
            String customerDisabledReason,
            String customerLevel,
            String customerSettlementMethod,
            Integer customerSettlementDays,
            Boolean customerTaxIncluded,
            String customerInvoiceType,
            BigDecimal customerTaxRate,
            String preferredProducts,
            String supplierStatus,
            String supplierDisabledReason,
            String supplierSettlementMethod,
            Integer supplierSettlementDays,
            Integer leadTimeDays,
            BigDecimal minOrderAmount,
            Boolean supplierTaxIncluded,
            String supplierInvoiceType,
            BigDecimal supplierTaxRate,
            String qualificationMemo
    ) {
    }

    public record ProductCreate(
            Long categoryId,
            @NotNull Long productSeriesId,
            Long specPresetId,
            String sku,
            @NotBlank String name,
            String grade,
            String temper,
            String standard,
            String spec,
            String attributeJson,
            String barcode,
            String originalNumber,
            String unit,
            BigDecimal defaultPrice,
            String status,
            List<ProductFieldValueCreate> fieldValues,
            String identityStatus
    ) {
        public ProductCreate(
                Long categoryId,
                Long productSeriesId,
                Long specPresetId,
                String sku,
                String name,
                String grade,
                String temper,
                String standard,
                String spec,
                String attributeJson,
                String barcode,
                String originalNumber,
                String unit,
                BigDecimal defaultPrice,
                String status,
                List<ProductFieldValueCreate> fieldValues
        ) {
            this(categoryId, productSeriesId, specPresetId, sku, name, grade, temper, standard,
                    spec, attributeJson, barcode, originalNumber, unit, defaultPrice, status,
                    fieldValues, null);
        }
    }

    public record ProductFieldValueCreate(
            @NotNull Long seriesFieldId,
            Object value
    ) {
    }

    public record WarehouseCreate(
            @NotBlank String name,
            String location,
            String receivingAddresses,
            String status
    ) {
    }
}
