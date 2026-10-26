package com.soyia.copperv2.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BusinessExportFormatTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void decimalNeverUsesScientificNotationAndPreservesScale() {
        assertEquals("123.4500", BusinessExportFormat.value(new BigDecimal("123.4500"), SHANGHAI));
        assertEquals("0.0000001", BusinessExportFormat.value(new BigDecimal("1E-7"), SHANGHAI));
    }

    @Test
    void timestampContainsConfiguredOffset() {
        assertEquals("2026-07-14T09:30+08:00",
                BusinessExportFormat.value(Timestamp.valueOf("2026-07-14 09:30:00"), SHANGHAI));
    }

    @Test
    void csvIsRfc4180QuotedWithoutChangingOriginalText() {
        assertEquals("\"a,\"\"b\"\"\r\n=SUM(1,1)\"", BusinessExportFormat.csv("a,\"b\"\r\n=SUM(1,1)"));
    }

    @Test
    void attachmentNameCannotEscapeItsDirectory() {
        String name = BusinessExportFormat.safeFileName("../../客户/回单\n.png");
        assertFalse(name.contains(".."));
        assertFalse(name.contains("/"));
        assertFalse(name.contains("\n"));
    }

    @Test
    void binaryValueCannotEnterCsv() {
        assertThrows(IllegalStateException.class, () -> BusinessExportFormat.value(new byte[]{1}, SHANGHAI));
    }
}
