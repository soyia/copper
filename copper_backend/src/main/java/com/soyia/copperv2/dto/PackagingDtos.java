package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class PackagingDtos {
    private PackagingDtos() {
    }

    public record Profile(
            Long productId,
            String productName,
            String baseUnit,
            String minimumSalesUnit,
            String minimumOutboundUnit,
            String defaultPurchaseUnit,
            String defaultSalesUnit,
            String roundingPolicy,
            Integer versionNo,
            List<Rule> rules,
            String sourceLevel,
            Boolean inherited,
            Long templateId,
            String templateName,
            Long templateRevisionId,
            Integer templateRevisionNo,
            Long productSeriesId,
            String productSeriesName,
            Long overrideRevisionId,
            Long effectiveRevisionId,
            String contentHash,
            List<String> overrideFields,
            List<String> warnings
    ) {
    }

    public record Rule(
            Long id,
            Long productId,
            Integer versionNo,
            String parentUnit,
            String childUnit,
            String conversionMode,
            BigDecimal fixedChildQuantity,
            BigDecimal referenceBaseQuantity,
            Boolean breakable,
            Boolean repackable,
            Boolean purchaseAllowed,
            Boolean salesAllowed,
            Boolean outboundAllowed,
            Boolean integerOnly,
            Integer sortNo,
            String status,
            String ruleKey,
            String sourceLevel,
            Boolean overridden
    ) {
    }

    public record ProfileSave(
            @NotNull Integer expectedVersionNo,
            String baseUnit,
            String minimumSalesUnit,
            String minimumOutboundUnit,
            String defaultPurchaseUnit,
            String defaultSalesUnit,
            String roundingPolicy,
            @Valid List<RuleSave> rules,
            @Valid List<PackageDefinitionSave> packageDefinitions,
            String changeReason
    ) {
    }

    public record RuleSave(
            @NotBlank String parentUnit,
            @NotBlank String childUnit,
            @NotBlank String conversionMode,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal fixedChildQuantity,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal referenceBaseQuantity,
            Boolean breakable,
            Boolean repackable,
            Boolean purchaseAllowed,
            Boolean salesAllowed,
            Boolean outboundAllowed,
            Boolean integerOnly,
            Integer sortNo,
            String status,
            String ruleKey
    ) {
        public RuleSave(String parentUnit, String childUnit, String conversionMode,
                        BigDecimal fixedChildQuantity, BigDecimal referenceBaseQuantity,
                        Boolean breakable, Boolean repackable, Boolean purchaseAllowed,
                        Boolean salesAllowed, Boolean outboundAllowed, Boolean integerOnly,
                        Integer sortNo, String status) {
            this(parentUnit, childUnit, conversionMode, fixedChildQuantity, referenceBaseQuantity,
                    breakable, repackable, purchaseAllowed, salesAllowed, outboundAllowed,
                    integerOnly, sortNo, status, null);
        }
    }

    public record PlanRequest(
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            String quantityUnit,
            @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity,
            String priceUnit,
            String purpose,
            Long expectedEffectiveRevisionId,
            String expectedSnapshotHash,
            Long supplierOfferRevisionId,
            Long packagingVariantRevisionId
    ) {
        public PlanRequest(BigDecimal quantity, String quantityUnit, BigDecimal actualBaseQuantity,
                           String priceUnit, String purpose) {
            this(quantity, quantityUnit, actualBaseQuantity, priceUnit, purpose,
                    null, null, null, null);
        }

        public PlanRequest(BigDecimal quantity, String quantityUnit, BigDecimal actualBaseQuantity,
                           String priceUnit, String purpose, Long expectedEffectiveRevisionId,
                           String expectedSnapshotHash) {
            this(quantity, quantityUnit, actualBaseQuantity, priceUnit, purpose,
                    expectedEffectiveRevisionId, expectedSnapshotHash, null, null);
        }
    }

    public record Plan(
            BigDecimal requestedQuantity,
            String requestedUnit,
            BigDecimal baseQuantity,
            String baseUnit,
            BigDecimal pricingQuantity,
            String priceUnit,
            Boolean actualWeightRequired,
            Boolean rounded,
            String roundingPolicy,
            String suggestion,
            List<Component> components,
            String snapshotJson,
            Boolean estimated,
            Long effectiveRevisionId,
            String snapshotHash,
            String quantityBasis
    ) {
    }

    public record Component(
            String unit,
            BigDecimal quantity,
            BigDecimal baseQuantity,
            String conversionMode
    ) {
    }

    public record PackageInstanceInput(
            String packageCode,
            @Digits(integer = 18, fraction = 6) BigDecimal actualBaseQuantity
    ) {
    }

    public record PackageInstance(
            Long id,
            Long batchId,
            Long productId,
            String packageCode,
            String unitName,
            BigDecimal actualBaseQuantity,
            BigDecimal remainingBaseQuantity,
            String baseUnit,
            String status
    ) {
    }

    public record TemplateRow(
            Long id,
            String code,
            String name,
            String description,
            String status,
            Long currentRevisionId,
            Integer currentRevisionNo,
            Integer lockVersion,
            Integer seriesCount,
            Integer productCount,
            String baseUnit,
            Integer ruleCount,
            Integer overrideProductCount
    ) {
    }

    public record TemplateDetail(
            TemplateRow template,
            Profile profile
    ) {
    }

    public record TemplateSave(
            @NotBlank
            @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "模板编码只能使用数字、英文字母、短横线和下划线")
            String code,
            @NotBlank String name,
            String description,
            String status,
            @NotNull Integer expectedRevisionNo,
            @Valid @NotNull ProfileSave profile,
            String changeReason
    ) {
    }

    public record TemplatePublish(
            @NotNull Integer expectedRevisionNo,
            @Valid @NotNull ProfileSave profile,
            String changeReason
    ) {
    }

    public record TemplateMetadataSave(
            @NotBlank String name,
            String description,
            @NotNull Integer expectedLockVersion
    ) {
    }

    public record TemplateStatusSave(
            @NotBlank String status,
            @NotNull Integer expectedLockVersion,
            @NotBlank String changeReason
    ) {
    }

    public record SeriesBinding(
            Long productSeriesId,
            String productSeriesName,
            String categoryName,
            Long templateId,
            String templateName,
            Long templateRevisionId,
            Integer templateRevisionNo,
            Integer lockVersion,
            String status,
            Integer productCount,
            Integer overrideProductCount,
            String changeReason,
            List<String> warnings
    ) {
    }

    public record SeriesBindingSave(
            @NotNull Long templateId,
            @NotNull Integer expectedLockVersion,
            String changeReason
    ) {
    }

    public record HistoryRow(
            Long effectiveRevisionId,
            Integer versionNo,
            String sourceLevel,
            Long templateId,
            Long templateRevisionId,
            Long overrideRevisionId,
            String contentHash,
            String changeReason,
            LocalDateTime publishedAt,
            String templateName,
            Integer templateRevisionNo,
            String baseUnit,
            String packagingSummary,
            String profileSnapshotJson
    ) {
    }

    public record TemplateRevisionRow(
            Long revisionId,
            Integer revisionNo,
            String status,
            String baseUnit,
            String contentHash,
            String changeReason,
            LocalDateTime publishedAt
    ) {
    }

    public record ProductSummary(
            Long productId,
            Integer versionNo,
            String sourceLevel,
            String templateName,
            Integer templateRevisionNo,
            Integer overrideCount,
            String contentHash,
            String packagingSummary,
            List<String> warnings
    ) {
    }

    public record PackageDefinition(
            Long productId,
            String packageUnit,
            String barcode,
            String shelfLifeMode,
            Integer shelfLifeValue,
            String shelfLifeUnit,
            Long revisionId,
            LocalDateTime updatedAt
    ) {
    }

    public record PackageDefinitionSave(
            @NotBlank String packageUnit,
            String barcode,
            @NotBlank String shelfLifeMode,
            @Min(1) Integer shelfLifeValue,
            String shelfLifeUnit
    ) {
    }

    public record PackageDefinitionsSave(
            @Valid @NotNull List<PackageDefinitionSave> definitions,
            @NotBlank String changeReason
    ) {
    }

    public record PackageBarcodeMatch(
            Long productId,
            String sku,
            String productName,
            String packageUnit,
            String barcode,
            String shelfLifeMode,
            Integer shelfLifeValue,
            String shelfLifeUnit
    ) {
    }
}
