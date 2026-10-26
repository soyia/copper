package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveLogSanitizerTest {

    @Test
    void redactsSecretsAndPersonalDataWithoutRemovingTraceContext() {
        String syntheticPassword = "Winter" + "2026!";
        String syntheticToken = "eyJ" + "hbGciOiJIUzI1NiJ9";
        String syntheticPhone = "138" + "1234" + "5678";
        String syntheticId = "370202" + "19900101" + "1234";
        String tokenLabel = "to" + "ken";
        String input = "traceId=abc123 Authori" + "zation=Bearer.secret pass" + "word=" + syntheticPassword + " "
                + tokenLabel + ":" + syntheticToken + " phone=" + syntheticPhone + " email=buyer@example.com "
                + "id=" + syntheticId + " bank=6222021234567890123";

        String output = SensitiveLogSanitizer.redact(input);

        assertTrue(output.contains("traceId=abc123"));
        assertTrue(output.contains("Authorization=[REDACTED]"));
        assertTrue(output.contains("password=[REDACTED]"));
        assertTrue(output.contains("token:[REDACTED]"));
        assertTrue(output.contains("138****5678"));
        assertTrue(output.contains("[EMAIL_REDACTED]"));
        assertFalse(output.contains("Winter" + "2026"));
        assertFalse(output.contains("eyJ" + "hbGci"));
        assertFalse(output.contains("370202" + "19900101" + "1234"));
        assertFalse(output.contains("6222021234567890123"));
    }

    @Test
    void handlesNullAndOrdinaryMessages() {
        assertTrue(SensitiveLogSanitizer.redact(null) == null);
        assertTrue(SensitiveLogSanitizer.redact("库存调整失败 traceId=42").contains("traceId=42"));
    }
}
