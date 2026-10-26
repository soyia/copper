package com.soyia.copperv2.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Last-line protection for ordinary application logs.
 *
 * <p>Business request/response bodies are intentionally not logged. This sanitizer is the
 * fallback for values that may still appear in framework messages or exception text.</p>
 */
public final class SensitiveLogSanitizer {

    private static final String REDACTED = "[REDACTED]";

    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|pwd|"
                    + "token|access[_-]?token|refresh[_-]?token|secret|api[_-]?key|credential|session)\\b"
                    + "[\\s\\\"']*[:=][\\s\\\"']*)([^\\s,;}\\\"']+)"
    );
    private static final Pattern BEARER = Pattern.compile(
            "(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+"
    );
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])"
    );
    private static final Pattern CHINA_ID = Pattern.compile(
            "(?<!\\d)(\\d{3})\\d{13}([0-9Xx]{2})(?!\\d)"
    );
    private static final Pattern CHINA_MOBILE = Pattern.compile(
            "(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)"
    );
    private static final Pattern BANK_ACCOUNT = Pattern.compile(
            "(?<!\\d)\\d{8,15}(\\d{4})(?!\\d)"
    );

    private SensitiveLogSanitizer() {
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String result = replaceSecretAssignments(value);
        result = BEARER.matcher(result).replaceAll("Bearer " + REDACTED);
        result = EMAIL.matcher(result).replaceAll("[EMAIL_REDACTED]");
        result = CHINA_ID.matcher(result).replaceAll("$1*************$2");
        result = CHINA_MOBILE.matcher(result).replaceAll("$1****$2");
        return BANK_ACCOUNT.matcher(result).replaceAll("************$1");
    }

    private static String replaceSecretAssignments(String value) {
        Matcher matcher = SECRET_ASSIGNMENT.matcher(value);
        StringBuffer output = new StringBuffer(value.length());
        while (matcher.find()) {
            matcher.appendReplacement(output, Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
