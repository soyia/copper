package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class UnitLookupNormalizerTest {
    @Test
    void normalizesNamesAbbreviationsAndAliasesExactlyLikeImportLookup() {
        assertEquals("kg", UnitLookupNormalizer.normalize("  K G "));
        assertEquals("ｍｍ2", UnitLookupNormalizer.normalize("ｍｍ 2"));
        assertEquals("平方毫米", UnitLookupNormalizer.normalize(" 平方　毫米 "));
        assertNotEquals("m", UnitLookupNormalizer.normalize("mm"));
    }
}
