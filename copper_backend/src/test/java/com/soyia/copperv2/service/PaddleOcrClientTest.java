package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PaddleOcrClientTest {
    @Test
    void consumesOnlyVisibleOcrTextAndConfidenceFromPaddleResponse() throws Exception {
        PaddleOcrClient client = new PaddleOcrClient(
                new ObjectMapper(), true, "http://127.0.0.1:8080/ocr",
                "PADDLEOCR_LOCAL", "PP-OCRv5", 30);
        byte[] response = """
                {
                  "errorCode": 0,
                  "result": {
                    "ocrResults": [{
                      "prunedResult": {
                        "rec_texts": ["产品名称 R410A光面小盘管", "规格 6.35*0.8", "重量 54.96 Kg"],
                        "rec_scores": [0.98, 0.96, 0.97],
                        "qr_payload": "must-not-be-consumed"
                      }
                    }]
                  }
                }
                """.getBytes(StandardCharsets.UTF_8);

        PaddleOcrClient.OcrResult result = client.parseResponse(response);

        assertEquals("""
                产品名称 R410A光面小盘管
                规格 6.35*0.8
                重量 54.96 Kg""", result.text());
        assertEquals("0.9700", result.confidence().toPlainString());
        assertFalse(result.text().contains("must-not-be-consumed"));
    }
}
