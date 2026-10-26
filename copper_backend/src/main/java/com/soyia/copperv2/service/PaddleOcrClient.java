package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter for a privately deployed PaddleOCR 3.x basic serving endpoint.
 *
 * <p>Only recognized visible text and confidence are consumed. Barcode and QR
 * decoding are deliberately outside this adapter and the package-label MVP.</p>
 */
@Service
public class PaddleOcrClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final URI endpoint;
    private final String engine;
    private final String model;
    private final Duration timeout;

    public PaddleOcrClient(
            ObjectMapper objectMapper,
            @Value("${copper.ocr.enabled:true}") boolean enabled,
            @Value("${copper.ocr.endpoint:http://127.0.0.1:8080/ocr}") String endpoint,
            @Value("${copper.ocr.engine:PADDLEOCR_LOCAL}") String engine,
            @Value("${copper.ocr.model:PP-OCRv6-medium}") String model,
            @Value("${copper.ocr.timeout-seconds:120}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.endpoint = URI.create(endpoint);
        this.engine = engine;
        this.model = model;
        this.timeout = Duration.ofSeconds(Math.max(5, Math.min(timeoutSeconds, 300)));
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public OcrResult recognize(byte[] imageBytes) {
        if (!enabled) {
            throw new BusinessException("标签拍照识别尚未配置，请先启用本地 PaddleOCR 服务");
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("file", Base64.getEncoder().encodeToString(imageBytes));
            payload.put("fileType", 1);
            payload.put("useDocOrientationClassify", true);
            payload.put("useDocUnwarping", true);
            payload.put("useTextlineOrientation", true);
            payload.put("visualize", false);
            byte[] requestBody = objectMapper.writeValueAsBytes(payload);
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BusinessException("标签识别服务返回异常状态：" + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException("标签识别被中断，请稍后重试");
        } catch (IOException | IllegalArgumentException ex) {
            throw new BusinessException("标签识别服务不可用，请检查本地 PaddleOCR 配置");
        }
    }

    OcrResult parseResponse(byte[] responseBytes) throws IOException {
        JsonNode root = objectMapper.readTree(responseBytes);
        int errorCode = root.path("errorCode").asInt(0);
        if (errorCode != 0) {
            String message = root.path("errorMsg").asText("未知错误");
            throw new BusinessException("标签识别失败：" + message);
        }
        List<String> texts = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        collectRecognizedLines(root, texts, scores);
        if (texts.isEmpty()) {
            throw new BusinessException("图片中未识别到可用的标签文字，请重拍并保持标签端正清晰");
        }
        double confidence = scores.isEmpty()
                ? 0.5
                : scores.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
        return new OcrResult(
                String.join("\n", texts),
                decimalConfidence(confidence),
                engine,
                model,
                sha256(responseBytes));
    }

    private void collectRecognizedLines(
            JsonNode node, List<String> texts, List<Double> scores) {
        if (node == null || node.isNull()) return;
        JsonNode recTexts = node.get("rec_texts");
        if (recTexts != null && recTexts.isArray()) {
            JsonNode recScores = node.get("rec_scores");
            for (int index = 0; index < recTexts.size(); index++) {
                String value = recTexts.get(index).asText("").trim();
                if (value.isEmpty()) continue;
                texts.add(value);
                if (recScores != null && recScores.isArray() && index < recScores.size()) {
                    scores.add(recScores.get(index).asDouble());
                }
            }
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectRecognizedLines(child, texts, scores));
            return;
        }
        if (node.isObject()) {
            Iterator<JsonNode> values = node.elements();
            while (values.hasNext()) collectRecognizedLines(values.next(), texts, scores);
        }
    }

    private java.math.BigDecimal decimalConfidence(double value) {
        double bounded = Math.max(0d, Math.min(value, 1d));
        return java.math.BigDecimal.valueOf(bounded)
                .setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public record OcrResult(
            String text,
            java.math.BigDecimal confidence,
            String engine,
            String model,
            String responseSha256
    ) {}
}
