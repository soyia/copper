package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class TrustedTimeSafetyService {
    private static final Duration MAX_SNAPSHOT_AGE = Duration.ofMinutes(15);
    private static final List<String> HIGH_RISK_PREFIXES = List.of(
            "/payments", "/supplier-payments", "/receivable-adjustments", "/acceptance-bills",
            "/partner-fund-adjustments", "/customer-advance-refunds", "/sales-returns", "/business-exports",
            "/system/users", "/system/roles"
    );
    private static final List<String> HIGH_RISK_TRANSITIONS = List.of(
            "/confirm", "/cancel", "/submit", "/withdraw", "/audit", "/settle", "/reverse",
            "/complete-information", "/prepayments/receive", "/prepayments/reserve", "/release",
            "/discount", "/mature", "/endorse", "/bounce"
    );

    private final ObjectMapper objectMapper;
    private final Path snapshotFile;
    private final boolean enabled;
    private final Clock clock;

    @Autowired
    public TrustedTimeSafetyService(
            ObjectMapper objectMapper,
            @Value("${copper.time-safety.file:}") String snapshotFile
    ) {
        this(objectMapper, snapshotFile, Clock.systemUTC());
    }

    TrustedTimeSafetyService(ObjectMapper objectMapper, String snapshotFile, Clock clock) {
        this.objectMapper = objectMapper;
        this.enabled = snapshotFile != null && !snapshotFile.isBlank();
        this.snapshotFile = enabled ? Path.of(snapshotFile).toAbsolutePath().normalize() : null;
        this.clock = clock;
    }

    public void assertHighRiskWriteAllowed(String method, String path) {
        if (!enabled || !requiresTrustedTime(method, path)) return;
        Snapshot snapshot = readSnapshot();
        if (snapshot == null) {
            throw blocked("可信时间状态尚未建立或无法验证");
        }
        if (Duration.between(snapshot.observedAt(), clock.instant()).abs().compareTo(MAX_SNAPSHOT_AGE) > 0) {
            throw blocked("可信时间状态已超过15分钟，必须先刷新");
        }
        if ("BLOCKED".equals(snapshot.level())) {
            throw blocked("系统时间偏差已进入阻断级别");
        }
    }

    static boolean requiresTrustedTime(String method, String path) {
        if (method == null || path == null || List.of("GET", "HEAD", "OPTIONS").contains(method.toUpperCase())) {
            return false;
        }
        if (HIGH_RISK_PREFIXES.stream().anyMatch(path::startsWith)) return true;
        return HIGH_RISK_TRANSITIONS.stream().anyMatch(path::endsWith)
                && (path.startsWith("/purchase-orders/") || path.startsWith("/inbounds/")
                || path.startsWith("/sales-orders/") || path.startsWith("/outbounds/")
                || path.startsWith("/sales-returns/") || path.startsWith("/receivable-adjustments/")
                || path.startsWith("/acceptance-bills/"));
    }

    private Snapshot readSnapshot() {
        if (snapshotFile == null || !Files.isRegularFile(snapshotFile) || Files.isSymbolicLink(snapshotFile)) return null;
        try {
            JsonNode root = objectMapper.readTree(snapshotFile.toFile());
            if (!"copper-time-safety/v1".equals(root.path("formatVersion").asText())) return null;
            String level = root.path("level").asText("");
            if (!List.of("NORMAL", "WARNING", "CRITICAL", "BLOCKED").contains(level)) return null;
            return new Snapshot(Instant.parse(root.path("observedAt").asText()), level);
        } catch (IOException | DateTimeParseException ex) {
            return null;
        }
    }

    private BusinessException blocked(String reason) {
        return new BusinessException(reason + "，资金、确认、审核、冲正和权限变更等高风险写入已安全停止；"
                + "请恢复NTP同步并运行 copperctl monitor check 复核后重试");
    }

    private record Snapshot(Instant observedAt, String level) {}
}
