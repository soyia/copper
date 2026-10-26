package com.soyia.copperv2.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCookieFactoryTest {
    @Test
    void createsProductionSessionCookieWithRequiredSecurityAttributes() {
        String cookie = new SessionCookieFactory(true, "Strict", "/api")
                .create("session-token", Duration.ofHours(12))
                .toString();

        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("Secure"));
        assertTrue(cookie.contains("SameSite=Strict"));
        assertTrue(cookie.contains("Path=/api"));
    }

    @Test
    void rejectsSameSiteNoneWithoutSecure() {
        assertThrows(IllegalStateException.class,
                () -> new SessionCookieFactory(false, "None", "/api"));
    }
}
