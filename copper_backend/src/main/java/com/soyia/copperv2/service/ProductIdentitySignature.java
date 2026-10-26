package com.soyia.copperv2.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable product-identity signature shared by candidate and stored-product evaluation.
 *
 * <p>Each dimension is folded into a SHA-256 chain with UTF-8 byte-length prefixes.
 * This makes the encoding unambiguous even when a dimension value contains separators
 * used by older display-oriented encodings, and avoids constructing one size-limited
 * aggregate string in the database migration.</p>
 */
final class ProductIdentitySignature {
    private static final String FORMAT_VERSION = "I2";

    private ProductIdentitySignature() {
    }

    static String calculate(int schemaVersion, List<Part> parts) {
        String signature = sha256(FORMAT_VERSION + lengthPrefixed(String.valueOf(schemaVersion)));
        for (Part part : parts) {
            signature = sha256(signature
                    + lengthPrefixed(part.dimensionKey())
                    + lengthPrefixed(part.canonicalValue()));
        }
        return signature;
    }

    private static String lengthPrefixed(String value) {
        String safe = value == null ? "" : value;
        return safe.getBytes(StandardCharsets.UTF_8).length + ":" + safe;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成商品身份签名", ex);
        }
    }

    record Part(String dimensionKey, String canonicalValue) {
    }
}
