package com.soyia.copperv2.config;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlywayMigrationFailureAnalyzerTest {
    private final FlywayMigrationFailureAnalyzer analyzer = new FlywayMigrationFailureAnalyzer();

    @Test
    void reportsFailedVersionAndSafeRecoveryActions() {
        FailureAnalysis analysis = analyzer.analyze(new IllegalStateException(
                "startup failed", new FlywayException("Migration V2__broken_probe.sql failed")));

        assertNotNull(analysis);
        assertTrue(analysis.getDescription().contains("不会进入就绪状态"));
        assertTrue(analysis.getDescription().contains("V2__broken_probe.sql"));
        assertTrue(analysis.getAction().contains("不要修改已发布迁移"));
        assertTrue(analysis.getAction().contains("恢复备份"));
    }

    @Test
    void ignoresUnrelatedStartupFailure() {
        assertNull(analyzer.analyze(new IllegalArgumentException("not flyway")));
    }
}
