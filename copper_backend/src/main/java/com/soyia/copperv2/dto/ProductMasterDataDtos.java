package com.soyia.copperv2.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Contracts for product identity governance and complex, versioned product master data.
 * Published revisions are immutable; an update always creates a new revision.
 */
public final class ProductMasterDataDtos {
    private ProductMasterDataDtos() {
    }

    public record ReferenceValue(
            Long id, String referenceType, String code, String name, String englishName,
            String issuingBody, String edition, LocalDate effectiveFrom, LocalDate effectiveTo,
            List<String> aliases, String status, Integer versionNo, String changeReason
    ) {
    }

    public record ReferenceValueSave(
            @NotBlank String referenceType,
            @NotBlank String code,
            @NotBlank String name,
            String englishName,
            String issuingBody,
            String edition,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            List<String> aliases,
            String status,
            @NotBlank String changeReason,
            Integer expectedVersionNo
    ) {
    }

    public record ReferenceResolveRequest(
            @NotBlank String referenceType,
            @NotBlank String sourceValue,
            Boolean allowUnresolved
    ) {
    }

    public record ReferenceResolution(
            Long referenceValueId,
            String referenceType,
            String sourceValue,
            String normalizedCode,
            String name,
            Boolean resolved,
            List<String> warnings
    ) {
    }

    public record IdentityRule(
            Long id, String dimensionKey, String sourceType, Long seriesFieldId,
            String referenceType, Boolean required, Integer sortNo, String status
    ) {
    }

    public record IdentityRuleSave(
            @NotBlank String dimensionKey,
            @NotBlank String sourceType,
            Long seriesFieldId,
            String referenceType,
            Boolean required,
            Integer sortNo,
            String status
    ) {
    }

    public record IdentitySchema(
            Long productSeriesId, String productSeriesName, Integer versionNo,
            List<IdentityRule> rules, String contentHash, String changeReason,
            LocalDateTime publishedAt
    ) {
    }

    public record IdentitySchemaSave(
            @NotNull Integer expectedVersion,
            @Valid @NotEmpty List<IdentityRuleSave> rules,
            @NotBlank String changeReason
    ) {
    }

    public record IdentitySchemaPreflight(
            @NotNull Integer expectedVersion,
            @Valid @NotEmpty List<IdentityRuleSave> rules
    ) {
    }

    public record IdentityCollision(
            String identitySignature, List<Long> productIds, List<String> skus
    ) {
    }

    public record IdentitySchemaPreflightResult(
            Long productSeriesId, Integer currentVersion, Integer proposedVersion,
            Integer collisionCount, Integer incompleteProductCount,
            List<IdentityCollision> collisions, List<String> warnings
    ) {
    }

    public record ProductDuplicateCheck(
            String result,
            Long existingProductId,
            String existingSku,
            String existingName,
            String identitySignature,
            Integer schemaVersion,
            Boolean complete,
            List<String> warnings
    ) {
    }

    public record ProductIdentityPublication(
            Long productId,
            Integer identitySchemaVersion,
            String identityStatus,
            String identitySignature
    ) {
    }

    public record AllowedUnit(
            Long id, Long productSeriesId, String purpose, Long unitId,
            String unitName, String abbreviation, Boolean defaultUnit,
            Integer sortNo, String status
    ) {
    }

    public record AllowedUnitSave(
            @NotBlank String purpose,
            @NotNull Long unitId,
            Boolean defaultUnit,
            Integer sortNo,
            String status
    ) {
    }

    public record SourceDocument(
            Long id, String documentCode, String title, String sourceType,
            Long currentRevisionId, Integer currentRevisionNo, String status,
            String sourceUrl, String fileName, String fileSha256, String mimeType,
            Long fileSize, LocalDate publicationDate, String internalVersion,
            LocalDateTime downloadedAt, String contentSnapshotJson,
            String changeReason, LocalDateTime publishedAt
    ) {
    }

    public record SourceDocumentRevision(
            Long id, Long documentId, String documentCode, Integer revisionNo,
            String titleSnapshot, String sourceTypeSnapshot, String sourceUrl,
            String fileName, String fileSha256, String mimeType, Long fileSize,
            LocalDate publicationDate, String internalVersion, LocalDateTime downloadedAt,
            String contentSnapshotJson, String headStatusSnapshot,
            Boolean headSnapshotAvailable, String revisionStatus, String changeReason,
            Long publishedBy, String publishedByName, LocalDateTime publishedAt,
            Boolean currentRevision
    ) {
    }

    public record SourceDocumentSave(
            String documentCode,
            @NotBlank String title,
            @NotBlank String sourceType,
            String sourceUrl,
            String fileName,
            String fileSha256,
            String mimeType,
            Long fileSize,
            LocalDate publicationDate,
            String internalVersion,
            LocalDateTime downloadedAt,
            String contentSnapshotJson,
            String status,
            @NotBlank String changeReason,
            Integer expectedRevisionNo
    ) {
    }

    public record SourceLink(
            Long id, Long documentRevisionId, Long documentId, String documentTitle,
            Integer documentRevisionNo, String targetType, Long targetId,
            Integer pageNo, String sourceLocator, String rawValue,
            String normalizedValue, BigDecimal confidence, String reviewStatus,
            String reviewComment, Long reviewedBy, String reviewedByName,
            LocalDateTime reviewedAt
    ) {
    }

    public record SourceLinkSave(
            @NotNull Long documentRevisionId,
            @NotBlank String targetType,
            @NotNull Long targetId,
            Integer pageNo,
            String sourceLocator,
            String rawValue,
            String normalizedValue,
            @DecimalMin("0.000000") @DecimalMax("1.000000")
            @Digits(integer = 1, fraction = 6) BigDecimal confidence
    ) {
    }

    public record SourceLinkReview(
            @NotBlank String reviewStatus,
            String reviewComment
    ) {
    }

    public record SourceLinkReviewHistory(
            Long id, Long sourceLinkId, String previousStatus, String reviewStatus,
            String reviewComment, Long reviewedBy, String reviewedByName,
            LocalDateTime reviewedAt
    ) {
    }

    public record PackagingVariant(
            Long id, Long productId, String variantCode, String name,
            Long currentRevisionId, Integer revisionNo, String packageUnit,
            String conversionMode, BigDecimal fixedBaseQuantity,
            BigDecimal referenceBaseQuantity, String innerSpec,
            BigDecimal netWeight, BigDecimal grossWeight, BigDecimal tareWeight,
            BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm,
            String barcode, Long supplierId, Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            String status, String changeReason, LocalDateTime publishedAt
    ) {
    }

    public record PackagingVariantRevision(
            Long id, Long packagingVariantId, Long productId, String variantCodeSnapshot,
            String nameSnapshot, String headStatusSnapshot, Boolean headSnapshotAvailable,
            Integer revisionNo, String packageUnit, String conversionMode,
            BigDecimal fixedBaseQuantity, BigDecimal referenceBaseQuantity, String innerSpec,
            BigDecimal netWeight, BigDecimal grossWeight, BigDecimal tareWeight,
            BigDecimal lengthMm, BigDecimal widthMm, BigDecimal heightMm,
            String barcode, Long supplierId, Long sourceDocumentId,
            Long sourceDocumentRevisionId, String contentHash, String revisionStatus,
            String changeReason, Long publishedBy, String publishedByName,
            LocalDateTime publishedAt, Boolean currentRevision
    ) {
    }

    public record PackagingVariantSave(
            String variantCode,
            @NotBlank String name,
            @NotBlank String packageUnit,
            @NotBlank String conversionMode,
            @DecimalMin(value = "0.000001", inclusive = true)
            @Digits(integer = 18, fraction = 6) BigDecimal fixedBaseQuantity,
            @DecimalMin(value = "0.000001", inclusive = true)
            @Digits(integer = 18, fraction = 6) BigDecimal referenceBaseQuantity,
            String innerSpec,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal netWeight,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal grossWeight,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal tareWeight,
            @DecimalMin("0.000") @Digits(integer = 15, fraction = 3) BigDecimal lengthMm,
            @DecimalMin("0.000") @Digits(integer = 15, fraction = 3) BigDecimal widthMm,
            @DecimalMin("0.000") @Digits(integer = 15, fraction = 3) BigDecimal heightMm,
            String barcode,
            Long supplierId,
            Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            String status,
            @NotBlank String changeReason,
            Integer expectedRevisionNo
    ) {
    }

    public record SupplierOffer(
            Long id, Long supplierId, String supplierName, Long productId,
            String supplierProductCode, String supplierModel,
            Long currentRevisionId, Integer revisionNo,
            String purchaseUnit, String priceUnit, BigDecimal quotedUnitPrice,
            String currencyCode,
            Boolean priceIncludesTax, BigDecimal taxRate,
            BigDecimal minimumOrderQuantity, BigDecimal orderMultiple,
            Integer leadTimeDays, LocalDate effectiveFrom, LocalDate effectiveTo,
            Long packagingVariantRevisionId, Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            String commercialTermsJson, String status, String changeReason,
            LocalDateTime publishedAt
    ) {
    }

    public record SupplierOfferRevision(
            Long id, Long supplierProductOfferId, Long supplierId,
            String supplierNameSnapshot, Long productId,
            String supplierProductCodeSnapshot, String supplierModelSnapshot,
            String headStatusSnapshot, Boolean headSnapshotAvailable,
            Integer revisionNo, String purchaseUnit, String priceUnit,
            BigDecimal quotedUnitPrice, String currencyCode,
            Boolean priceIncludesTax, BigDecimal taxRate,
            BigDecimal minimumOrderQuantity, BigDecimal orderMultiple,
            Integer leadTimeDays, LocalDate effectiveFrom, LocalDate effectiveTo,
            Long packagingVariantRevisionId, Long sourceDocumentId,
            Long sourceDocumentRevisionId, String commercialTermsJson,
            String contentHash, String revisionStatus, String changeReason,
            Long publishedBy, String publishedByName, LocalDateTime publishedAt,
            Boolean currentRevision
    ) {
    }

    public record SupplierOfferSave(
            @NotNull Long supplierId,
            String supplierProductCode,
            String supplierModel,
            @NotBlank String purchaseUnit,
            @NotBlank String priceUnit,
            @DecimalMin(value = "0.00000001", inclusive = true)
            @Digits(integer = 16, fraction = 8) BigDecimal quotedUnitPrice,
            String currencyCode,
            Boolean priceIncludesTax,
            @DecimalMin("0.000000") @DecimalMax("100.000000")
            @Digits(integer = 3, fraction = 6) BigDecimal taxRate,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal minimumOrderQuantity,
            @DecimalMin("0.000000") @Digits(integer = 18, fraction = 6) BigDecimal orderMultiple,
            @Min(0) Integer leadTimeDays,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long packagingVariantRevisionId,
            Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            String commercialTermsJson,
            String status,
            @NotBlank String changeReason,
            Integer expectedRevisionNo
    ) {
    }

    public record Port(
            Long id, Long productId, Integer portNo, String portRole,
            String connectionType, String gender, String nominalSize,
            BigDecimal nominalSizeMm, String pressureClass, BigDecimal angleDegree,
            String standardCode, String materialCode, Integer sortNo, String status
    ) {
    }

    public record PortSave(
            @NotNull @Min(1) Integer portNo,
            String portRole,
            @NotBlank String connectionType,
            String gender,
            @NotBlank String nominalSize,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal nominalSizeMm,
            String pressureClass,
            @Digits(integer = 6, fraction = 3) BigDecimal angleDegree,
            String standardCode,
            String materialCode,
            Integer sortNo,
            String status
    ) {
    }

    public record ConductorComponent(
            Long id, Integer layerNo, String componentType, String materialCode,
            Integer strandCount, BigDecimal diameterMm, BigDecimal thicknessMm,
            String layDirection, BigDecimal layPitchMm, Integer sortNo, String status
    ) {
    }

    public record ConductorComponentSave(
            @NotNull @Min(1) Integer layerNo,
            @NotBlank String componentType,
            String materialCode,
            @Min(0) Integer strandCount,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal diameterMm,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal thicknessMm,
            String layDirection,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal layPitchMm,
            Integer sortNo,
            String status
    ) {
    }

    public record ConductorConstruction(
            Long id, Long productId, String constructionType,
            BigDecimal nominalCrossSectionMm2, Integer strandCount,
            BigDecimal singleWireDiameterMm, BigDecimal conductorWidthMm,
            BigDecimal conductorThicknessMm, String layDirection,
            BigDecimal layPitchMm, String thermalClass,
            String insulationSystemCode, String constructionSignature,
            String status, List<ConductorComponent> components
    ) {
    }

    public record ConductorConstructionSave(
            @NotBlank String constructionType,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal nominalCrossSectionMm2,
            @Min(0) Integer strandCount,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal singleWireDiameterMm,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal conductorWidthMm,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal conductorThicknessMm,
            String layDirection,
            @DecimalMin("0.000001") @Digits(integer = 12, fraction = 6) BigDecimal layPitchMm,
            String thermalClass,
            String insulationSystemCode,
            String status,
            @Valid List<ConductorComponentSave> components
    ) {
    }

    public record TechnicalCharacteristic(
            Long id, String characteristicCode, String name, String valueType,
            String targetText, BigDecimal minimumValue, BigDecimal targetValue,
            BigDecimal maximumValue, String unitName, String testMethod,
            String testCondition, Boolean batchRequired, Integer sortNo
    ) {
    }

    public record TechnicalCharacteristicSave(
            @NotBlank String characteristicCode,
            @NotBlank String name,
            @NotBlank String valueType,
            String targetText,
            @Digits(integer = 20, fraction = 10) BigDecimal minimumValue,
            @Digits(integer = 20, fraction = 10) BigDecimal targetValue,
            @Digits(integer = 20, fraction = 10) BigDecimal maximumValue,
            String unitName,
            String testMethod,
            String testCondition,
            Boolean batchRequired,
            Integer sortNo
    ) {
    }

    public record TechnicalSpec(
            Long id, Long productId, Integer revisionNo, String standardCode,
            LocalDate effectiveFrom, LocalDate effectiveTo, Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            String status, String changeReason, LocalDateTime publishedAt,
            List<TechnicalCharacteristic> characteristics
    ) {
    }

    public record TechnicalSpecSave(
            String standardCode,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            @NotBlank String changeReason,
            @Valid @NotEmpty List<TechnicalCharacteristicSave> characteristics,
            Integer expectedRevisionNo
    ) {
    }

    public record QualityResult(
            Long id, Long technicalCharacteristicId, String characteristicCode,
            String measuredText, BigDecimal measuredValue, String unitName,
            String conclusion, String testMethodSnapshot, LocalDateTime testedAt
    ) {
    }

    public record QualityResultSave(
            @NotNull Long technicalCharacteristicId,
            @NotBlank String characteristicCode,
            String measuredText,
            @Digits(integer = 20, fraction = 10) BigDecimal measuredValue,
            String unitName,
            String conclusion,
            String testMethodSnapshot,
            LocalDateTime testedAt
    ) {
    }

    public record QualityCertificate(
            Long id, Long inventoryBatchId, String certificateNo, String certificateType,
            Long supplierId, String manufacturerName, String manufacturerBatchNo,
            String heatNo, String coilNo, LocalDate issueDate, Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            Long fileArchiveId, String verificationStatus, String status,
            Integer lockVersion, String reviewComment, Long reviewedBy,
            String reviewedByName, LocalDateTime reviewedAt,
            List<QualityResult> results
    ) {
    }

    public record QualityCertificateSave(
            @NotBlank String certificateNo,
            String certificateType,
            Long supplierId,
            String manufacturerName,
            String manufacturerBatchNo,
            String heatNo,
            String coilNo,
            LocalDate issueDate,
            Long sourceDocumentId,
            Long sourceDocumentRevisionId,
            Long fileArchiveId,
            String status,
            @Valid List<QualityResultSave> results,
            Integer expectedLockVersion
    ) {
    }

    public record QualityCertificateReviewSave(
            @NotBlank String reviewStatus,
            String reviewComment,
            @NotNull Integer expectedLockVersion
    ) {
    }

    public record QualityCertificateReviewHistory(
            Long id, Long certificateId, String previousStatus, String reviewStatus,
            String reviewComment, Long reviewedBy, String reviewedByName,
            LocalDateTime reviewedAt
    ) {
    }

    public record BomItem(
            Long id, Long componentProductId, String componentSku, String componentName,
            BigDecimal quantity, String unit, BigDecimal baseQuantity, String baseUnit,
            Long packagingEffectiveRevisionId, String conversionSnapshotJson,
            BigDecimal scrapRate,
            String issueMethod, Integer sortNo
    ) {
    }

    public record BomItemSave(
            @NotNull Long componentProductId,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal quantity,
            @NotBlank String unit,
            @DecimalMin("0.000000") @DecimalMax("100.000000")
            @Digits(integer = 3, fraction = 6) BigDecimal scrapRate,
            String issueMethod,
            Integer sortNo
    ) {
    }

    public record Bom(
            Long id, Long parentProductId, String bomCode, String name,
            Long currentRevisionId, Integer revisionNo, BigDecimal outputQuantity,
            String outputUnit, BigDecimal outputBaseQuantity, String outputBaseUnit,
            Long outputEffectiveRevisionId, String outputConversionSnapshotJson,
            BigDecimal scrapRate, LocalDate effectiveFrom,
            LocalDate effectiveTo, String status, String changeReason,
            LocalDateTime publishedAt, List<BomItem> items
    ) {
    }

    public record BomRevision(
            Long id, Long bomId, Long parentProductId,
            String bomCodeSnapshot, String nameSnapshot,
            String headStatusSnapshot, Boolean headSnapshotAvailable,
            Integer revisionNo, BigDecimal outputQuantity,
            String outputUnit, BigDecimal outputBaseQuantity, String outputBaseUnit,
            Long outputEffectiveRevisionId, String outputConversionSnapshotJson,
            BigDecimal scrapRate, LocalDate effectiveFrom, LocalDate effectiveTo,
            String contentHash, String revisionStatus, String changeReason,
            Long publishedBy, String publishedByName, LocalDateTime publishedAt,
            Boolean currentRevision, List<BomItem> items
    ) {
    }

    public record BomSave(
            String bomCode,
            @NotBlank String name,
            @NotNull @DecimalMin("0.000001") @Digits(integer = 18, fraction = 6) BigDecimal outputQuantity,
            @NotBlank String outputUnit,
            @DecimalMin("0.000000") @DecimalMax("100.000000")
            @Digits(integer = 3, fraction = 6) BigDecimal scrapRate,
            @NotNull LocalDate effectiveFrom,
            LocalDate effectiveTo,
            String status,
            @NotBlank String changeReason,
            @Valid @NotEmpty List<BomItemSave> items,
            Integer expectedRevisionNo
    ) {
    }
}
