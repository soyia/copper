package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HongtaiPackageLabelParserTest {
    @Test
    void preservesThePrintedWeightWithoutRoundingInventoryQuantitiesToMoneyPrecision() {
        var field = new HongtaiPackageLabelParser().parse("净重 20.345678 Kg", BigDecimal.ONE).stream()
                .filter(row -> row.code().equals("actual_weight_kg")).findFirst().orElseThrow();
        assertEquals("20.345678", field.value());
        assertEquals("净重 20.345678 Kg", field.excerpt());
    }

    @Test
    void extractsHongtaiVisibleLabelFieldsWithoutAnyQrCandidate() {
        String text = """
                青岛宏泰金属制品有限公司
                产品名称 R410A光面小盘管
                规格 6.35*0.8【宏泰精品】
                重量 54.96 Kg
                生产日期 2025-05-27
                卷号 254409726-16
                二维码下方的内容不参与解析
                """;

        Map<String, String> fields = new HongtaiPackageLabelParser()
                .parse(text, new BigDecimal("0.9321"))
                .stream().collect(Collectors.toMap(
                        HongtaiPackageLabelParser.ParsedField::code,
                        HongtaiPackageLabelParser.ParsedField::value));

        assertEquals("HONGTAI_PACKAGE_LABEL_V1", fields.get("ocr_template"));
        assertEquals("R410A光面小盘管", fields.get("product_name"));
        assertEquals("6.35*0.8【宏泰精品】", fields.get("specification"));
        assertEquals("54.96", fields.get("actual_weight_kg"));
        assertEquals("2025-05-27", fields.get("production_date"));
        assertEquals("254409726-16", fields.get("package_code"));
        assertFalse(fields.keySet().stream().anyMatch(
                key -> key.toLowerCase().contains("qr")));
    }
}
