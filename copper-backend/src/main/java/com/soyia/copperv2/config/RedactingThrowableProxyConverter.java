package com.soyia.copperv2.config;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public final class RedactingThrowableProxyConverter extends ThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SensitiveLogSanitizer.redact(super.convert(event));
    }
}
