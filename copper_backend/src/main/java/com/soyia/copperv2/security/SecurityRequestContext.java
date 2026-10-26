package com.soyia.copperv2.security;

import jakarta.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class SecurityRequestContext {
    private static final ThreadLocal<Metadata> CURRENT = new ThreadLocal<>();

    private SecurityRequestContext() {}

    public static Metadata begin(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (request.getContextPath() != null && !request.getContextPath().isBlank() && path.startsWith(request.getContextPath())) {
            path = path.substring(request.getContextPath().length());
        }
        Metadata metadata = new Metadata(
                UUID.randomUUID().toString().replace("-", ""),
                safe(request.getRemoteAddr(), 64),
                safe(request.getMethod(), 10),
                safe(path, 255),
                sha256(request.getHeader("User-Agent"))
        );
        CURRENT.set(metadata);
        return metadata;
    }

    public static Metadata get() { return CURRENT.get(); }
    public static void clear() { CURRENT.remove(); }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String safe(String value, int max) {
        String text = value == null ? "" : value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }

    public record Metadata(String traceId, String ipAddress, String requestMethod, String requestPath, String userAgentHash) {}
}
