package com.soyia.copperv2.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.soyia.copperv2.dto.StorageCapacityDtos.Filesystem;
import com.soyia.copperv2.dto.StorageCapacityDtos.LastSuccessfulBackup;
import com.soyia.copperv2.dto.StorageCapacityDtos.Policy;
import com.soyia.copperv2.dto.StorageCapacityDtos.Target;
import com.soyia.copperv2.dto.StorageCapacityDtos.Usage;
import com.soyia.copperv2.dto.StorageCapacityDtos.View;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Service
public class StorageCapacityService {
    private static final Logger log = LoggerFactory.getLogger(StorageCapacityService.class);
    private static final Duration MAX_SNAPSHOT_AGE = Duration.ofMinutes(15);
    private static final long GIB = 1024L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataRoot;
    private final Path fileRoot;
    private final Path exportRoot;
    private final Path snapshotFile;

    public StorageCapacityService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            @Value("${copper.runtime.data-root}") String dataRoot,
            @Value("${copper.file-storage.root-path}") String fileRoot,
            @Value("${copper.export.root-path}") String exportRoot,
            @Value("${copper.storage.capacity-file}") String snapshotFile
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataRoot = Path.of(dataRoot).toAbsolutePath().normalize();
        this.fileRoot = Path.of(fileRoot).toAbsolutePath().normalize();
        this.exportRoot = Path.of(exportRoot).toAbsolutePath().normalize();
        this.snapshotFile = Path.of(snapshotFile).toAbsolutePath().normalize();
    }

    public View view() {
        RuntimeFilesystem runtime = runtimeFilesystem();
        JsonNode root = readSnapshot();
        Instant observedAt = instant(root, "observedAt");
        boolean stale = observedAt == null || Duration.between(observedAt, Instant.now()).compareTo(MAX_SNAPSHOT_AGE) > 0;
        Usage usage = root == null ? fallbackUsage() : usage(root.path("usage"));
        List<Target> targets = root == null ? List.of() : targets(root.path("targets"));
        LastSuccessfulBackup lastBackup = root == null ? null : lastBackup(root.path("lastSuccessfulBackup"));
        String statusMessage = root == null
                ? "尚未收到部署层容量快照；当前仅显示后端可直接核对的数据盘容量"
                : stale ? "部署层容量快照超过15分钟，请在服务器运行 copperctl storage status 刷新" : "容量状态正常同步";
        return new View(
                "copper-capacity/v1",
                observedAt,
                stale,
                runtime.riskLevel(),
                usage,
                new Filesystem(runtime.capacityBytes(), runtime.availableBytes(), runtime.riskLevel()),
                targets,
                lastBackup,
                new Policy(
                        "CUSTOMER_STORAGE",
                        false,
                        "低于20%或5 GiB",
                        "低于10%或2 GiB；禁止向该目标备份，并禁止导出和附件上传",
                        "低于5%或1 GiB；业务写入进入受控只读"
                ),
                statusMessage
        );
    }

    public void assertLargeWriteAllowed(String operationName) {
        String risk = runtimeFilesystem().riskLevel();
        if ("CRITICAL".equals(risk) || "DANGER".equals(risk)) {
            throw new BusinessException("当前数据盘空间已进入" + riskName(risk) + "级别，已安全阻止" + operationName
                    + "；请先清理可删除的旧备份/导出或扩容，再运行 copperctl storage status 复核");
        }
    }

    public void assertBusinessWriteAllowed() {
        String risk = runtimeFilesystem().riskLevel();
        if ("DANGER".equals(risk)) {
            throw new BusinessException("当前数据盘空间已进入危险级别，为避免数据库和附件损坏，业务写入已受控停止；"
                    + "请先扩容或清理安全可删除文件，并运行 copperctl storage status 复核");
        }
    }

    static String classify(long capacityBytes, long availableBytes) {
        if (capacityBytes <= 0 || availableBytes < 0) return "UNKNOWN";
        double percent = availableBytes * 100.0 / capacityBytes;
        if (availableBytes < GIB || percent < 5) return "DANGER";
        if (availableBytes < 2 * GIB || percent < 10) return "CRITICAL";
        if (availableBytes < 5 * GIB || percent < 20) return "WARNING";
        return "NORMAL";
    }

    private RuntimeFilesystem runtimeFilesystem() {
        try {
            FileStore store = Files.getFileStore(dataRoot);
            long capacity = store.getTotalSpace();
            long available = store.getUsableSpace();
            return new RuntimeFilesystem(capacity, available, classify(capacity, available));
        } catch (IOException ex) {
            log.warn("无法读取数据盘容量 path={} type={}", dataRoot, ex.getClass().getSimpleName());
            return new RuntimeFilesystem(0, 0, "UNKNOWN");
        }
    }

    private JsonNode readSnapshot() {
        if (!Files.isRegularFile(snapshotFile) || Files.isSymbolicLink(snapshotFile)) return null;
        try {
            JsonNode root = objectMapper.readTree(snapshotFile.toFile());
            if (!"copper-capacity/v1".equals(root.path("formatVersion").asText())) {
                log.warn("忽略未知容量快照格式 path={}", snapshotFile);
                return null;
            }
            return root;
        } catch (IOException ex) {
            log.warn("容量快照读取失败 path={} type={}", snapshotFile, ex.getClass().getSimpleName());
            return null;
        }
    }

    private Usage fallbackUsage() {
        Long databaseBytes = jdbc.queryForObject("""
                SELECT COALESCE(SUM(data_length + index_length), 0)
                FROM information_schema.tables WHERE table_schema=DATABASE()
                """, Long.class);
        return new Usage(
                databaseBytes == null ? 0 : databaseBytes,
                directoryBytes(fileRoot),
                directoryBytes(exportRoot),
                0,
                directoryBytes(dataRoot.resolve("logs"))
        );
    }

    private long directoryBytes(Path root) {
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) return 0;
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> !Files.isSymbolicLink(path)).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException ignored) {
                    return 0;
                }
            }).sum();
        } catch (IOException ex) {
            return 0;
        }
    }

    private Usage usage(JsonNode node) {
        return new Usage(
                nonNegative(node, "databaseBytes"),
                nonNegative(node, "attachmentBytes"),
                nonNegative(node, "exportBytes"),
                nonNegative(node, "backupBytes"),
                nonNegative(node, "logBytes")
        );
    }

    private List<Target> targets(JsonNode node) {
        if (!node.isArray()) return List.of();
        List<Target> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(new Target(
                    item.path("target").asText(""),
                    item.path("type").asText(""),
                    item.path("location").asText(""),
                    nonNegative(item, "backupCount"),
                    nonNegative(item, "backupBytes"),
                    nullableNonNegative(item, "capacityBytes"),
                    nullableNonNegative(item, "availableBytes"),
                    item.path("quotaKnown").asBoolean(false),
                    item.path("quotaMessage").asText(""),
                    item.path("riskLevel").asText("UNKNOWN"),
                    item.path("available").asBoolean(true),
                    item.path("errorCode").asText("")
            ));
        }
        return List.copyOf(result);
    }

    private LastSuccessfulBackup lastBackup(JsonNode node) {
        if (!node.isObject()) return null;
        Instant completedAt = instant(node, "completedAt");
        String archive = node.path("archive").asText("");
        if (completedAt == null || archive.isBlank() || archive.contains("/") || archive.contains("\\")) return null;
        return new LastSuccessfulBackup(node.path("backupId").asText(""), completedAt, archive);
    }

    private Instant instant(JsonNode node, String field) {
        if (node == null) return null;
        String value = node.path(field).asText("");
        if (value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private long nonNegative(JsonNode node, String field) {
        return Math.max(0, node.path(field).asLong(0));
    }

    private Long nullableNonNegative(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : Math.max(0, value.asLong(0));
    }

    private String riskName(String risk) {
        return "DANGER".equals(risk) ? "危险" : "严重";
    }

    private record RuntimeFilesystem(long capacityBytes, long availableBytes, String riskLevel) {}
}
