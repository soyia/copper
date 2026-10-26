package com.soyia.copperv2.service;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

final class BusinessExportFormat {
    private BusinessExportFormat() {}

    static String value(Object value, ZoneId zoneId) {
        if (value == null) return "";
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Double number) return BigDecimal.valueOf(number).toPlainString();
        if (value instanceof Float number) return new BigDecimal(number.toString()).toPlainString();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().atZone(zoneId).toOffsetDateTime().toString();
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Time time) return time.toLocalTime().toString();
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toString();
        if (value instanceof LocalDateTime localDateTime) return localDateTime.atZone(zoneId).toOffsetDateTime().toString();
        if (value instanceof LocalDate localDate) return localDate.toString();
        if (value instanceof Instant instant) return instant.atZone(zoneId).toOffsetDateTime().toString();
        if (value instanceof byte[]) throw new IllegalStateException("二进制字段不得直接写入 CSV");
        return String.valueOf(value);
    }

    static String csv(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\"", "\"\"") + '"';
    }

    static String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "attachment.bin";
        name = name.replace('\\', '_').replace('/', '_').replaceAll("[\\p{Cntrl}]", "_");
        while (name.contains("..")) name = name.replace("..", "_");
        if (name.length() > 160) name = name.substring(name.length() - 160);
        return name.isBlank() ? "attachment.bin" : name;
    }
}
