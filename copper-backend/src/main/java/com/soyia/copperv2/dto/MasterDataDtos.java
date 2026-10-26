package com.soyia.copperv2.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class MasterDataDtos {
    public record ProductCategoryRow(
            Long id,
            Long parentId,
            String name,
            Integer sortNo,
            String status
    ) {
    }

    public record ProductCategoryCreate(
            Long parentId,
            @NotBlank String name,
            Integer sortNo,
            String status
    ) {
    }

    public record ProductStandardRow(
            Long id,
            String code,
            String name,
            String englishName,
            String standardStatus,
            String ccs,
            String ics,
            LocalDate publishDate,
            LocalDate implementationDate,
            LocalDate abolitionDate,
            String competentDepartment,
            String responsibleDepartment,
            String publisher,
            String memo,
            String sourceUrl,
            String status
    ) {
    }

    public record ProductStandardCreate(
            @NotBlank String code,
            String name,
            String englishName,
            String standardStatus,
            String ccs,
            String ics,
            LocalDate publishDate,
            LocalDate implementationDate,
            LocalDate abolitionDate,
            String competentDepartment,
            String responsibleDepartment,
            String publisher,
            String memo,
            String sourceUrl,
            String status
    ) {
    }

    public record ProductTemperRow(
            Long id,
            String name,
            String code,
            String englishName,
            String status
    ) {
    }

    public record ProductTemperCreate(
            @NotBlank String name,
            String code,
            String englishName,
            String status
    ) {
    }

    public record CustomFieldRow(
            Long id,
            String name,
            String fieldType,
            String valueType,
            Long unitCategoryId,
            String optionJson,
            String status,
            Long seriesCount,
            Long productCount,
            Long presetCount
    ) {
    }

    public record CustomFieldUsageRow(
            Long seriesFieldId,
            Long productSeriesId,
            String productSeriesName,
            Long categoryId,
            String categoryName,
            String displayName,
            Long unitId,
            String unitName,
            Boolean required,
            Boolean skuDimension,
            Boolean includeInSpec,
            Boolean searchable,
            Boolean orderVisible,
            String status,
            Long productCount,
            Long presetCount
    ) {
    }

    public record CustomFieldCreate(
            @NotBlank String name,
            String fieldType,
            String valueType,
            Long unitCategoryId,
            String optionJson,
            String status
    ) {
    }

    public record SeriesFieldRow(
            Long id,
            Long productSeriesId,
            Long fieldId,
            String fieldName,
            String displayName,
            String fieldType,
            String valueType,
            String optionJson,
            Long unitCategoryId,
            Long unitId,
            String unitName,
            Boolean required,
            Boolean skuDimension,
            Boolean includeInSpec,
            Boolean searchable,
            Boolean orderVisible,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer decimalScale,
            Integer sortNo,
            String status,
            Long productCount,
            Long presetCount
    ) {
    }

    public record SeriesFieldCreate(
            @NotNull Long productSeriesId,
            @NotNull Long fieldId,
            String displayName,
            String optionJson,
            Long unitCategoryId,
            Long unitId,
            String unitName,
            Boolean required,
            Boolean skuDimension,
            Boolean includeInSpec,
            Boolean searchable,
            Boolean orderVisible,
            BigDecimal minValue,
            BigDecimal maxValue,
            Integer decimalScale,
            Integer sortNo,
            String status
    ) {
    }

    public record SpecPresetRow(
            Long id,
            Long productSeriesId,
            String productSeriesName,
            String name,
            String model,
            String attributeJson,
            List<SpecPresetValueRow> fieldValues,
            String specText,
            String skuSuffix,
            Integer sortNo,
            String status
    ) {
    }

    public record SpecPresetValueRow(
            Long seriesFieldId,
            Long fieldId,
            String fieldName,
            Object value
    ) {
    }

    public record SpecPresetValueCreate(
            @NotNull Long seriesFieldId,
            Object value
    ) {
    }

    public record SpecPresetCreate(
            @NotNull Long productSeriesId,
            @NotBlank String name,
            @NotBlank String model,
            String attributeJson,
            List<SpecPresetValueCreate> fieldValues,
            String specText,
            String skuSuffix,
            Integer sortNo,
            String status
    ) {
    }
}
