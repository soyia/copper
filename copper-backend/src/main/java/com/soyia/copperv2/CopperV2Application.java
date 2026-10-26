package com.soyia.copperv2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class CopperV2Application {
    public static void main(String[] args) {
        configureTimeZone();
        SpringApplication.run(CopperV2Application.class, args);
    }

    static void configureTimeZone() {
        String configured = System.getenv("COPPER_TIME_ZONE");
        if (configured == null || configured.isBlank()) return;
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(configured.trim())));
        } catch (DateTimeException ex) {
            throw new IllegalStateException("COPPER_TIME_ZONE 不是有效时区，例如 Asia/Shanghai", ex);
        }
    }
}
