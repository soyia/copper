package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PaddleOcrLiveAcceptanceTest {
    @Test
    void recognizesHongtaiLabelWithoutUsingQrAsProductIdentity() throws Exception {
        String imagePath = System.getenv("COPPER_OCR_LIVE_TEST_IMAGE");
        assumeTrue(imagePath != null && !imagePath.isBlank(),
                "Set COPPER_OCR_LIVE_TEST_IMAGE to run the local PaddleOCR acceptance");

        PaddleOcrClient client = new PaddleOcrClient(
                new ObjectMapper(), true, "http://127.0.0.1:8080/ocr",
                "PADDLEOCR_LOCAL", "PP-OCRv6-medium", 120);
        PaddleOcrClient.OcrResult ocr = client.recognize(
                Files.readAllBytes(Path.of(imagePath)));
        Map<String, String> fields = new HongtaiPackageLabelParser()
                .parse(ocr.text(), ocr.confidence())
                .stream()
                .collect(Collectors.toMap(
                        HongtaiPackageLabelParser.ParsedField::code,
                        HongtaiPackageLabelParser.ParsedField::value));

        assertEquals("R410A光面小盘管", fields.get("product_name"));
        assertEquals("6.35*0.8{宏泰精品}", fields.get("specification"));
        assertEquals("54.96", fields.get("actual_weight_kg"));
        assertEquals("2025-05-27", fields.get("production_date"));
        assertEquals("254409726-16", fields.get("package_code"));
        assertFalse(fields.values().contains("1012505270898"));
        assertEquals("HONGTAI_PACKAGE_LABEL_V1", fields.get("ocr_template"));
    }
}
