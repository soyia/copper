package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedTimeSafetyServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-15T01:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void blocksHighRiskFinanceWriteWhenClockIsBlocked() throws Exception {
        TrustedTimeSafetyService service = service("2026-07-15T00:59:00Z", "BLOCKED");
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.assertHighRiskWriteAllowed("POST", "/partner-fund-adjustments/1/confirm"));
        assertTrue(failure.getMessage().contains("高风险写入已安全停止"));
        assertThrows(BusinessException.class,
                () -> service.assertHighRiskWriteAllowed("POST", "/sales-returns"));
    }

    @Test
    void blocksMissingOrStaleSnapshotButNotOrdinaryMasterData() throws Exception {
        TrustedTimeSafetyService missing = new TrustedTimeSafetyService(
                new ObjectMapper(), temporary.resolve("missing.json").toString(), fixedClock());
        assertThrows(BusinessException.class,
                () -> missing.assertHighRiskWriteAllowed("POST", "/payments"));

        TrustedTimeSafetyService stale = service("2026-07-15T00:30:00Z", "NORMAL");
        assertThrows(BusinessException.class,
                () -> stale.assertHighRiskWriteAllowed("POST", "/sales-orders/7/confirm"));
        assertDoesNotThrow(() -> stale.assertHighRiskWriteAllowed("POST", "/partners"));
    }

    @Test
    void allowsFreshNonBlockedSnapshotAndReadRequests() throws Exception {
        TrustedTimeSafetyService normal = service("2026-07-15T00:59:00Z", "NORMAL");
        TrustedTimeSafetyService critical = service("2026-07-15T00:59:00Z", "CRITICAL");
        assertDoesNotThrow(() -> normal.assertHighRiskWriteAllowed("POST", "/payments"));
        assertDoesNotThrow(() -> critical.assertHighRiskWriteAllowed("POST", "/payments"));
        assertDoesNotThrow(() -> normal.assertHighRiskWriteAllowed("GET", "/payments"));
        assertTrue(TrustedTimeSafetyService.requiresTrustedTime("POST", "/outbounds/8/reverse"));
    }

    @Test
    void disabledGuardKeepsStandaloneDevelopmentCompatible() {
        TrustedTimeSafetyService disabled = new TrustedTimeSafetyService(new ObjectMapper(), "", fixedClock());
        assertDoesNotThrow(() -> disabled.assertHighRiskWriteAllowed("POST", "/payments"));
    }

    @Test
    void productionConstructorIsExplicitlyInjectableWhenTestConstructorAlsoExists() {
        boolean injectable = Arrays.stream(TrustedTimeSafetyService.class.getDeclaredConstructors())
                .filter(constructor -> constructor.getParameterCount() == 2)
                .anyMatch(constructor -> constructor.isAnnotationPresent(Autowired.class));
        assertTrue(injectable);
    }

    private TrustedTimeSafetyService service(String observedAt, String level) throws Exception {
        Path file = temporary.resolve(level + "-" + observedAt.replace(':', '-') + ".json");
        Files.writeString(file, """
                {"formatVersion":"copper-time-safety/v1","observedAt":"%s","level":"%s"}
                """.formatted(observedAt, level));
        return new TrustedTimeSafetyService(new ObjectMapper(), file.toString(), fixedClock());
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
