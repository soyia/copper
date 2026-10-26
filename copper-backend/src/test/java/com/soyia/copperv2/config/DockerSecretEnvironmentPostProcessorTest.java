package com.soyia.copperv2.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSecretEnvironmentPostProcessorTest {
    @TempDir
    Path tempDir;

    private final DockerSecretEnvironmentPostProcessor processor = new DockerSecretEnvironmentPostProcessor();

    @Test
    void loadsSingleLineDatabasePasswordFromAbsoluteFile() throws Exception {
        Path secretFile = tempDir.resolve("db-password");
        Files.writeString(secretFile, "a-strong-database-password-2026\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_FILE, secretFile.toString());

        processor.postProcessEnvironment(environment, null);

        assertEquals("a-strong-database-password-2026",
                environment.getProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_VALUE));
        assertEquals("file", environment.getProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_SOURCE));
    }

    @Test
    void rejectsDirectAndFilePasswordAtTheSameTime() throws Exception {
        Path secretFile = tempDir.resolve("db-password");
        Files.writeString(secretFile, "a-strong-database-password-2026");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_FILE, secretFile.toString())
                .withProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_VALUE, "another-password");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> processor.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("不能同时配置"));
    }

    @Test
    void rejectsMultilineSecret() throws Exception {
        Path secretFile = tempDir.resolve("db-password");
        Files.writeString(secretFile, "first-line\nsecond-line\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty(DockerSecretEnvironmentPostProcessor.PASSWORD_FILE, secretFile.toString());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> processor.postProcessEnvironment(environment, null));

        assertTrue(error.getMessage().contains("单行密码"));
    }
}
