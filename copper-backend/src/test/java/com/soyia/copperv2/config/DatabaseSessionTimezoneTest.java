package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSessionTimezoneTest {
    @Test
    void mysqlConnectionsDefaultToChinaBusinessTimezone() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertNotNull(input);
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(yaml.contains("connection-init-sql: \"SET time_zone = '${COPPER_DB_TIME_ZONE_OFFSET:+08:00}'\""));
        }
    }
}
