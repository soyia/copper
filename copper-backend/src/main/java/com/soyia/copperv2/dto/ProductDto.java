package com.soyia.copperv2.dto;

import java.math.BigDecimal;
import java.util.List;

public record ProductDto(
        Long id,
        Long categoryId,
        String categoryName,
        Long productSeriesId,
        String productSeriesName,
        Long specPresetId,
        String specSignature,
        String sku,
        String name,
        String grade,
        String temper,
        String standard,
        String spec,
        String orderSpecText,
        String attributeJson,
        String barcode,
        String originalNumber,
        String unit,
        BigDecimal defaultPrice,
        String status,
        Integer identitySchemaVersionSnapshot,
        String identityStatus,
        String identitySignature,
        List<ProductFieldValueDto> fieldValues,
        Boolean masterDataComplete,
        List<String> missingRequiredFields
) {
    public record ProductFieldValueDto(
            Long seriesFieldId,
            Long fieldId,
            Long productSeriesId,
            String fieldName,
            String displayName,
            Object value,
            String displayValue,
            String fieldType,
            Long unitId,
            String unitName,
            Boolean skuDimension,
            Boolean includeInSpec,
            Boolean searchable,
            Boolean orderVisible,
            String seriesFieldStatus
    ) {
    }
}
