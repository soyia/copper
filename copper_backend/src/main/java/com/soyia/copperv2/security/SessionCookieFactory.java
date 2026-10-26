package com.soyia.copperv2.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;

@Component
public class SessionCookieFactory {
    private static final Set<String> SUPPORTED_SAME_SITE = Set.of("Strict", "Lax", "None");

    private final boolean secure;
    private final String sameSite;
    private final String path;

    public SessionCookieFactory(
            @Value("${copper.security.cookie.secure}") boolean secure,
            @Value("${copper.security.cookie.same-site}") String sameSite,
            @Value("${copper.security.cookie.path:/api}") String path
    ) {
        String normalizedSameSite = normalizeSameSite(sameSite);
        if ("None".equals(normalizedSameSite) && !secure) {
            throw new IllegalStateException("SameSite=None 的会话 Cookie 必须启用 Secure");
        }
        if (path == null || !path.startsWith("/")) {
            throw new IllegalStateException("会话 Cookie path 必须以 / 开头");
        }
        this.secure = secure;
        this.sameSite = normalizedSameSite;
        this.path = path;
    }

    public ResponseCookie create(String token, Duration maxAge) {
        return ResponseCookie.from(AuthService.SESSION_COOKIE, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite(sameSite)
                .path(path)
                .maxAge(maxAge)
                .build();
    }

    private String normalizeSameSite(String value) {
        if (value == null || value.isBlank()) throw new IllegalStateException("会话 Cookie SameSite 不能为空");
        String candidate = value.trim().toLowerCase(Locale.ROOT);
        String normalized = Character.toUpperCase(candidate.charAt(0)) + candidate.substring(1);
        if (!SUPPORTED_SAME_SITE.contains(normalized)) {
            throw new IllegalStateException("会话 Cookie SameSite 仅支持 Strict、Lax 或 None");
        }
        return normalized;
    }
}
