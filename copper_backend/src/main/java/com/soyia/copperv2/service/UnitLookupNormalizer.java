package com.soyia.copperv2.service;

import java.util.Locale;

/**
 * Canonical lookup token for unit names, abbreviations and aliases.
 */
final class UnitLookupNormalizer {
    private UnitLookupNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("(?U)\\s+", "");
    }
}
