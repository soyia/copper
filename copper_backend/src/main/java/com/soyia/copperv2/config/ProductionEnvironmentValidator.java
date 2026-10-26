package com.soyia.copperv2.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

public class ProductionEnvironmentValidator implements EnvironmentPostProcessor, Ordered {
    private static final Set<String> SAFE_LOG_LEVELS = Set.of("INFO", "WARN", "ERROR");
    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "123456", "12345678", "password", "password123", "admin", "admin123", "root", "root123456"
    );

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!Arrays.asList(environment.getActiveProfiles()).contains("prod")) return;

        String dataRootValue = required(environment, "COPPER_DATA_ROOT", "持久数据根目录");
        Path dataRoot = existingWritableDirectory(dataRootValue, "COPPER_DATA_ROOT");
        if (dataRoot.equals(dataRoot.getRoot()) || isTemporaryPath(dataRoot)) {
            fail("COPPER_DATA_ROOT 必须是独立持久目录，禁止使用文件系统根目录或 /tmp");
        }

        validateDatabase(environment);
        validatePort(required(environment, "COPPER_PORT", "业务端口"));
        validateTimeZone(required(environment, "COPPER_TIME_ZONE", "时区"));
        validateLogLevel(required(environment, "COPPER_LOG_LEVEL", "日志级别"));
        validateOrigins(required(environment, "COPPER_ALLOWED_ORIGINS", "前端来源"));
        validateCookie(environment);

        Path attachmentPath = existingWritableDirectory(
                required(environment, "COPPER_FILE_STORAGE_PATH", "附件目录"), "COPPER_FILE_STORAGE_PATH");
        Path uploadTempPath = existingWritableDirectory(
                required(environment, "COPPER_UPLOAD_TEMP_PATH", "上传临时目录"), "COPPER_UPLOAD_TEMP_PATH");
        String exportPathValue = environment.getProperty("COPPER_EXPORT_PATH");
        Path exportPath = absolutePath(
                exportPathValue == null || exportPathValue.isBlank()
                        ? dataRoot.resolve("exports").toString()
                        : exportPathValue,
                "COPPER_EXPORT_PATH");
        Path logFile = writableFileParent(required(environment, "COPPER_LOG_FILE", "日志文件"), "COPPER_LOG_FILE");
        Path credentialFile = writableFileParent(
                required(environment, "COPPER_BOOTSTRAP_CREDENTIAL_FILE", "首次管理员凭据文件"),
                "COPPER_BOOTSTRAP_CREDENTIAL_FILE");
        Path capacityFile = writableFileParent(
                required(environment, "COPPER_STORAGE_CAPACITY_FILE", "存储容量状态文件"),
                "COPPER_STORAGE_CAPACITY_FILE");

        requireUnderDataRoot(dataRoot, attachmentPath, "COPPER_FILE_STORAGE_PATH");
        requireUnderDataRoot(dataRoot, uploadTempPath, "COPPER_UPLOAD_TEMP_PATH");
        requireUnderDataRoot(dataRoot, exportPath, "COPPER_EXPORT_PATH");
        requireUnderDataRoot(dataRoot, logFile, "COPPER_LOG_FILE");
        requireUnderDataRoot(dataRoot, credentialFile, "COPPER_BOOTSTRAP_CREDENTIAL_FILE");
        requireUnderDataRoot(dataRoot, capacityFile, "COPPER_STORAGE_CAPACITY_FILE");

        if ("false".equalsIgnoreCase(environment.getProperty("COPPER_FLYWAY_ENABLED", "true"))) {
            fail("生产环境禁止关闭 Flyway 数据库迁移");
        }
        if ("true".equalsIgnoreCase(environment.getProperty("COPPER_FLYWAY_BASELINE_ON_MIGRATE", "false"))) {
            fail("生产环境禁止自动 baseline 非空数据库");
        }
    }

    private void validateDatabase(ConfigurableEnvironment environment) {
        String url = required(environment, "COPPER_DB_URL", "数据库地址");
        String username = required(environment, "COPPER_DB_USERNAME", "数据库用户名");
        String password = required(environment, DockerSecretEnvironmentPostProcessor.PASSWORD_VALUE, "数据库密码");
        if (!url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql://")) {
            fail("COPPER_DB_URL 必须使用 jdbc:mysql:// 地址");
        }
        if (url.toLowerCase(Locale.ROOT).matches(".*[?&](password|passwd|pwd)=.*")) {
            fail("COPPER_DB_URL 禁止内嵌数据库密码");
        }
        if ("root".equalsIgnoreCase(username)) {
            fail("生产环境禁止使用 MySQL root 账号，请创建最小权限业务账号");
        }
        if (!"file".equals(environment.getProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_SOURCE))) {
            fail("生产环境数据库密码必须通过 COPPER_DB_PASSWORD_FILE 读取，禁止直接写入环境变量");
        }
        if (password.length() < 16 || COMMON_PASSWORDS.contains(password.toLowerCase(Locale.ROOT))) {
            fail("生产环境数据库密码至少 16 位，且不能使用公共弱口令");
        }
    }

    private void validatePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1024 || port > 65535) fail("COPPER_PORT 必须在 1024-65535 之间");
        } catch (NumberFormatException ex) {
            fail("COPPER_PORT 必须是有效数字");
        }
    }

    private void validateTimeZone(String value) {
        try {
            ZoneId.of(value);
        } catch (DateTimeException ex) {
            fail("COPPER_TIME_ZONE 不是有效时区，例如 Asia/Shanghai");
        }
    }

    private void validateLogLevel(String value) {
        if (!SAFE_LOG_LEVELS.contains(value.toUpperCase(Locale.ROOT))) {
            fail("生产日志级别仅允许 INFO、WARN 或 ERROR，禁止 DEBUG/TRACE 输出敏感参数");
        }
    }

    private void validateOrigins(String value) {
        for (String origin : value.split(",")) {
            String normalized = origin.trim().toLowerCase(Locale.ROOT);
            if (normalized.isBlank() || normalized.contains("*") || !normalized.startsWith("https://")) {
                fail("生产环境 COPPER_ALLOWED_ORIGINS 必须是明确的 HTTPS 来源，禁止通配符");
            }
        }
    }

    private void validateCookie(ConfigurableEnvironment environment) {
        if (!"true".equalsIgnoreCase(required(environment, "COPPER_COOKIE_SECURE", "Cookie Secure 开关"))) {
            fail("生产环境会话 Cookie 必须启用 Secure");
        }
        if (!"strict".equalsIgnoreCase(required(environment, "COPPER_COOKIE_SAME_SITE", "Cookie SameSite"))) {
            fail("生产环境会话 Cookie 必须使用 SameSite=Strict");
        }
    }

    private String required(ConfigurableEnvironment environment, String key, String description) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) fail("缺少生产配置 " + key + "（" + description + "）");
        return value.trim();
    }

    private Path existingWritableDirectory(String value, String key) {
        Path path = absolutePath(value, key);
        if (!Files.isDirectory(path) || !Files.isWritable(path)) {
            fail(key + " 必须是已存在且可写的目录");
        }
        return path;
    }

    private Path writableFileParent(String value, String key) {
        Path path = absolutePath(value, key);
        Path parent = path.getParent();
        if (parent == null || !Files.isDirectory(parent) || !Files.isWritable(parent)) {
            fail(key + " 的父目录必须已存在且可写");
        }
        return path;
    }

    private Path absolutePath(String value, String key) {
        try {
            Path original = Path.of(value);
            if (!original.isAbsolute()) fail(key + " 必须使用绝对路径");
            return original.toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            fail(key + " 不是有效路径");
            throw ex;
        }
    }

    private void requireUnderDataRoot(Path dataRoot, Path path, String key) {
        if (!path.startsWith(dataRoot)) fail(key + " 必须位于 COPPER_DATA_ROOT 持久目录内");
    }

    private boolean isTemporaryPath(Path path) {
        return path.startsWith(Path.of("/tmp")) || path.startsWith(Path.of("/var/tmp"));
    }

    private void fail(String message) {
        throw new IllegalStateException("Copper 生产配置校验失败：" + message);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
