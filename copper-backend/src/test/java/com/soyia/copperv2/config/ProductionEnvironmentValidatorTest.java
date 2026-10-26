package com.soyia.copperv2.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionEnvironmentValidatorTest {
    @TempDir
    Path tempDir;

    private final ProductionEnvironmentValidator validator = new ProductionEnvironmentValidator();
    private Path persistentTestRoot;

    @AfterEach
    void cleanPersistentTestRoot() throws Exception {
        if (persistentTestRoot != null) {
            FileSystemUtils.deleteRecursively(persistentTestRoot);
        }
    }

    @Test
    void acceptsCompleteProductionConfiguration() throws Exception {
        MockEnvironment environment = validEnvironment();

        assertDoesNotThrow(() -> validator.postProcessEnvironment(environment, null));
    }

    @Test
    void rejectsRootDatabaseAccount() throws Exception {
        MockEnvironment environment = validEnvironment().withProperty("COPPER_DB_USERNAME", "root");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("禁止使用 MySQL root"));
    }

    @Test
    void rejectsInsecureProductionCookie() throws Exception {
        MockEnvironment environment = validEnvironment().withProperty("COPPER_COOKIE_SECURE", "false");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("必须启用 Secure"));
    }

    @Test
    void rejectsDebugLogging() throws Exception {
        MockEnvironment environment = validEnvironment().withProperty("COPPER_LOG_LEVEL", "DEBUG");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("禁止 DEBUG/TRACE"));
    }

    @Test
    void rejectsBusinessPathOutsidePersistentRoot() throws Exception {
        MockEnvironment environment = validEnvironment()
                .withProperty("COPPER_FILE_STORAGE_PATH", tempDir.resolve("outside-files").toString());
        Files.createDirectories(tempDir.resolve("outside-files"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("必须位于 COPPER_DATA_ROOT"));
    }

    @Test
    void rejectsExportPathOutsidePersistentRoot() throws Exception {
        MockEnvironment environment = validEnvironment()
                .withProperty("COPPER_EXPORT_PATH", tempDir.resolve("outside-exports").toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> validator.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("COPPER_EXPORT_PATH 必须位于 COPPER_DATA_ROOT"));
    }

    private MockEnvironment validEnvironment() throws Exception {
        Path persistentTestParent = Files.createDirectories(
                Path.of("target", "test-persistent").toAbsolutePath());
        persistentTestRoot = Files.createTempDirectory(persistentTestParent, "production-environment-");
        Path dataRoot = Files.createDirectories(persistentTestRoot.resolve("persistent-data"));
        Path files = Files.createDirectories(dataRoot.resolve("files"));
        Path uploadTemp = Files.createDirectories(dataRoot.resolve("tmp"));
        Path logs = Files.createDirectories(dataRoot.resolve("logs"));
        Path runtime = Files.createDirectories(dataRoot.resolve("runtime"));
        Path secrets = Files.createDirectories(dataRoot.resolve("secrets"));
        Path passwordFile = persistentTestRoot.resolve("db-password");
        Files.writeString(passwordFile, "a-strong-database-password-2026");

        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment
                .withProperty("COPPER_DATA_ROOT", dataRoot.toString())
                .withProperty("COPPER_DB_URL", "jdbc:mysql://mysql:3306/copper_v2")
                .withProperty("COPPER_DB_USERNAME", "copper_app")
                .withProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_FILE, passwordFile.toString())
                .withProperty("COPPER_PORT", "8191")
                .withProperty("COPPER_TIME_ZONE", "Asia/Shanghai")
                .withProperty("COPPER_LOG_LEVEL", "INFO")
                .withProperty("COPPER_LOG_FILE", logs.resolve("backend.log").toString())
                .withProperty("COPPER_FILE_STORAGE_PATH", files.toString())
                .withProperty("COPPER_UPLOAD_TEMP_PATH", uploadTemp.toString())
                .withProperty("COPPER_STORAGE_CAPACITY_FILE", runtime.resolve("storage-capacity.json").toString())
                .withProperty("COPPER_BOOTSTRAP_CREDENTIAL_FILE", secrets.resolve("initial-admin.txt").toString())
                .withProperty("COPPER_ALLOWED_ORIGINS", "https://copper.example.com")
                .withProperty("COPPER_COOKIE_SECURE", "true")
                .withProperty("COPPER_COOKIE_SAME_SITE", "Strict");
        new DockerSecretEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        return environment;
    }
}
