package com.soyia.copperv2.service;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PackageLabelProductMatcher {
    private static final Pattern BRACED_NOTE = Pattern.compile(
            "[{｛\\[【][^}｝\\]】]*[}｝\\]】]");
    private static final Pattern DIRECT_DIMENSION_PAIR = Pattern.compile(
            "(?i)(?<![\\d.])(\\d+(?:\\.\\d+)?)\\s*(?:mm|毫米)?\\s*[*×xＸ]\\s*"
                    + "(\\d+(?:\\.\\d+)?)\\s*(?:mm|毫米)?");
    private static final Pattern OUTER_DIAMETER = Pattern.compile(
            "(?i)(?:外径|直径|OD|[Φφ])\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:mm|毫米)?");
    private static final Pattern WALL_THICKNESS = Pattern.compile(
            "(?i)(?:壁厚|WT)\\s*[:：]?\\s*(\\d+(?:\\.\\d+)?)\\s*(?:mm|毫米)?");

    private PackageLabelProductMatcher() {
    }

    static boolean matches(
            String labelName,
            String labelSpecification,
            String productName,
            String productSpecification) {
        return compatibleName(labelName, productName)
                && compatibleSpecification(labelSpecification, productSpecification);
    }

    private static boolean compatibleName(String labelValue, String productValue) {
        String label = normalizedName(labelValue);
        String product = normalizedName(productValue);
        return !label.isEmpty() && !product.isEmpty()
                && (label.contains(product) || product.contains(label));
    }

    private static boolean compatibleSpecification(String labelValue, String productValue) {
        Optional<DimensionPair> labelDimensions = dimensions(labelValue);
        Optional<DimensionPair> productDimensions = dimensions(productValue);
        if (labelDimensions.isPresent() && productDimensions.isPresent()) {
            return labelDimensions.get().sameAs(productDimensions.get());
        }
        String label = normalizedSpecification(labelValue);
        String product = normalizedSpecification(productValue);
        return !label.isEmpty() && !product.isEmpty()
                && (label.contains(product) || product.contains(label));
    }

    private static Optional<DimensionPair> dimensions(String value) {
        String safeValue = value == null ? "" : value;
        Matcher direct = DIRECT_DIMENSION_PAIR.matcher(safeValue);
        if (direct.find()) {
            return Optional.of(new DimensionPair(
                    decimal(direct.group(1)), decimal(direct.group(2))));
        }
        Matcher outerDiameter = OUTER_DIAMETER.matcher(safeValue);
        Matcher wallThickness = WALL_THICKNESS.matcher(safeValue);
        if (outerDiameter.find() && wallThickness.find()) {
            return Optional.of(new DimensionPair(
                    decimal(outerDiameter.group(1)), decimal(wallThickness.group(1))));
        }
        return Optional.empty();
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value).stripTrailingZeros();
    }

    private static String normalizedName(String value) {
        return normalizedText(value)
                // “紫铜”是材质通称，宏泰标签常省略，不能据此判定为另一商品。
                .replace("紫铜", "");
    }

    private static String normalizedSpecification(String value) {
        return normalizedText(BRACED_NOTE.matcher(value == null ? "" : value).replaceAll(""));
    }

    private static String normalizedText(String value) {
        return (value == null ? "" : value).trim().toUpperCase(Locale.ROOT)
                .replace('×', '*')
                .replace('Ｘ', '*')
                .replaceAll("[\\s/\\\\()（）\\[\\]【】{}｛｝,，:：;；._\\-]", "");
    }

    private record DimensionPair(BigDecimal outerDiameter, BigDecimal wallThickness) {
        private boolean sameAs(DimensionPair other) {
            return outerDiameter.compareTo(other.outerDiameter) == 0
                    && wallThickness.compareTo(other.wallThickness) == 0;
        }
    }
}
