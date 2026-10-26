package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageLabelProductMatcherTest {
    @Test
    void matchesHongtaiCompactLabelAgainstCurrentProductMaster() {
        assertTrue(PackageLabelProductMatcher.matches(
                "R410A光面小盘管",
                "6.35*0.8{宏泰精品}",
                "R410A紫铜光面小盘管",
                "外径6.35mm / 壁厚0.8mm / 牌号/材质TP2 / 材料状态O60 / 执行标准GB/T 17791-2017"));
    }

    @Test
    void rejectsDifferentWallThicknessEvenWhenNameMatches() {
        assertFalse(PackageLabelProductMatcher.matches(
                "R410A光面小盘管",
                "6.35*1.0{宏泰精品}",
                "R410A紫铜光面小盘管",
                "外径6.35mm / 壁厚0.8mm / 牌号/材质TP2"));
    }

    @Test
    void rejectsDifferentProductNameEvenWhenDimensionsMatch() {
        assertFalse(PackageLabelProductMatcher.matches(
                "R32光面小盘管",
                "6.35*0.8{宏泰精品}",
                "R410A紫铜光面小盘管",
                "外径6.35mm / 壁厚0.8mm"));
    }

    @Test
    void acceptsEquivalentCompactDimensionFormatting() {
        assertTrue(PackageLabelProductMatcher.matches(
                "R410A紫铜光面小盘管",
                "6.350 × 0.800 mm【宏泰精品】",
                "R410A光面小盘管",
                "6.35*0.8"));
    }
}
