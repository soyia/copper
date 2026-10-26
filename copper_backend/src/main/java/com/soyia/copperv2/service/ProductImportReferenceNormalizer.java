package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.ProductMasterDataDtos;
import org.springframework.util.StringUtils;

import java.util.function.BiFunction;

/**
 * Converts imported reference aliases to the same canonical codes used by product master data.
 * Import never creates a reference value implicitly.
 */
final class ProductImportReferenceNormalizer {
    private ProductImportReferenceNormalizer() {
    }

    static Result normalize(
            String referenceType,
            String sourceValue,
            BiFunction<String, String, ProductMasterDataDtos.ReferenceResolution> resolver) {
        if (!StringUtils.hasText(sourceValue)) return new Result("", "", "");
        try {
            ProductMasterDataDtos.ReferenceResolution resolution =
                    resolver.apply(referenceType, sourceValue.trim());
            if (!Boolean.TRUE.equals(resolution.resolved())
                    || !StringUtils.hasText(resolution.normalizedCode())) {
                return new Result("", "UNKNOWN_REFERENCE_VALUE",
                        "主数据值未配置；导入不会静默创建牌号、状态或标准");
            }
            return new Result(resolution.normalizedCode(), "", "");
        } catch (BusinessException ex) {
            return new Result("", "REFERENCE_RESOLUTION_FAILED",
                    StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "主数据值解析失败");
        }
    }

    record Result(String normalizedValue, String conflictType, String message) {
        boolean resolved() {
            return !StringUtils.hasText(conflictType);
        }
    }
}
