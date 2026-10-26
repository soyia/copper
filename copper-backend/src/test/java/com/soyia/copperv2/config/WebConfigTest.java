package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebConfigTest {
    @Test
    void parsesExplicitOriginsAndRejectsWildcard() {
        assertEquals(List.of("https://a.example.com", "https://b.example.com"),
                WebConfig.parseOrigins("https://a.example.com, https://b.example.com"));
        assertThrows(IllegalStateException.class, () -> WebConfig.parseOrigins("https://*.example.com"));
    }

    @Test
    void privateNetworkPatternsCoverOnlyRfc1918Ipv4Ranges() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(WebConfig.privateNetworkOriginPatterns());

        for (String origin : List.of(
                "http://10.20.30.40:8076",
                "https://10.0.0.1",
                "http://172.16.0.1:8076",
                "https://172.31.255.254:443",
                "http://192.168.1.64:8076")) {
            assertEquals(origin, configuration.checkOrigin(origin));
        }
        for (String origin : List.of(
                "http://172.15.255.254:8076",
                "http://172.32.0.1:8076",
                "http://192.169.1.1:8076",
                "http://8.8.8.8:8076",
                "https://example.com")) {
            assertNull(configuration.checkOrigin(origin));
        }
    }
}
