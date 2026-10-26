package com.soyia.copperv2.service;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class HongtaiPackageLabelParser {
    private static final List<FieldSpec> FIELD_SPECS = List.of(
            new FieldSpec("product_name", "标签产品名称", "产品名称|品名", "[^\\n]{2,100}"),
            new FieldSpec("specification", "标签规格", "规格(?:型号)?", "[^\\n]{1,100}"),
            new FieldSpec("actual_weight_kg", "实际重量（kg）", "重量|净重",
                    "[0-9,.]+\\s*(?:kg|KG|千克)?"),
            new FieldSpec("production_date", "生产日期", "生产日期|制造日期",
                    "\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?"),
            new FieldSpec("package_code", "包装卷号/序号", "卷号|序号|盘号",
                    "[A-Za-z0-9\\-_]{2,80}")
    );

    public List<ParsedField> parse(String text, BigDecimal extractionConfidence) {
        List<ParsedField> result = new ArrayList<>();
        result.add(new ParsedField(
                "ocr_template", "识别模板", "HONGTAI_PACKAGE_LABEL_V1",
                new BigDecimal("1.0000"), "HONGTAI_PACKAGE_LABEL_V1"));
        for (FieldSpec spec : FIELD_SPECS) {
            Pattern pattern = Pattern.compile(
                    "(?im)(?:" + spec.labelPattern() + ")\\s*[:：]?\\s*("
                            + spec.valuePattern() + ")");
            Matcher matcher = pattern.matcher(text);
            String value = matcher.find() ? normalize(spec.code(), matcher.group(1).trim()) : "";
            result.add(new ParsedField(
                    spec.code(), spec.label(), value,
                    value.isEmpty() ? new BigDecimal("0.0000")
                            : extractionConfidence.min(new BigDecimal("0.8500")),
                    value.isEmpty() ? "" : matcher.group(0).trim()));
        }
        String summary = text.length() > 500 ? text.substring(0, 500) : text;
        result.add(new ParsedField(
                "raw_summary", "识别原文摘要", summary,
                summary.isEmpty() ? new BigDecimal("0.0000")
                        : extractionConfidence.min(new BigDecimal("0.5000")),
                summary));
        return List.copyOf(result);
    }

    private String normalize(String code, String value) {
        if (!StringUtils.hasText(value)) return "";
        if ("actual_weight_kg".equals(code)) {
            String numeric = value.replaceAll("(?i)kg|千克|,", "").trim();
            try {
                return new BigDecimal(numeric).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        if ("production_date".equals(code)) {
            Matcher matcher = Pattern.compile(
                    "(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?").matcher(value);
            if (matcher.matches()) {
                try {
                    return LocalDate.of(
                            Integer.parseInt(matcher.group(1)),
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3))).toString();
                } catch (RuntimeException ignored) {
                    return "";
                }
            }
        }
        return value.trim();
    }

    private record FieldSpec(
            String code, String label, String labelPattern, String valuePattern) {}

    public record ParsedField(
            String code, String label, String value,
            BigDecimal confidence, String excerpt) {}
}
