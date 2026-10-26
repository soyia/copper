package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.ProductMasterDataDtos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductImportReferenceNormalizerTest {
    @Test
    void normalizesConfiguredMagneticMaterialGradeThroughOfficialGradeReference() {
        ProductImportReferenceNormalizer.Result result =
                ProductImportReferenceNormalizer.normalize("GRADE", " n52 ",
                        (type, source) -> new ProductMasterDataDtos.ReferenceResolution(
                                52L, type, source, "N52", "N52", true, List.of()));

        assertTrue(result.resolved());
        assertEquals("N52", result.normalizedValue());
    }

    @Test
    void rejectsUnknownMagneticMaterialGradeInsteadOfCreatingItSilently() {
        ProductImportReferenceNormalizer.Result result =
                ProductImportReferenceNormalizer.normalize("GRADE", "未配置磁材牌号",
                        (type, source) -> new ProductMasterDataDtos.ReferenceResolution(
                                null, type, source, "", "", false, List.of("未匹配")));

        assertFalse(result.resolved());
        assertEquals("UNKNOWN_REFERENCE_VALUE", result.conflictType());
        assertTrue(result.message().contains("不会静默创建"));
    }
}
