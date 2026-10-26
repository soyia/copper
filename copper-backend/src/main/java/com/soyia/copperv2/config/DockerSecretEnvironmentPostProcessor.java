package com.soyia.copperv2.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class DockerSecretEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    static final String PASSWORD_FILE = "COPPER_DB_PASSWORD_FILE";
    static final String PASSWORD_VALUE = "COPPER_DB_PASSWORD";
    static final String PASSWORD_SOURCE = "copper.security.db-password-source";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String fileValue = environment.getProperty(PASSWORD_FILE);
        String directValue = environment.getProperty(PASSWORD_VALUE);
        if (hasText(fileValue) && hasText(directValue)) {
            throw new IllegalStateException("COPPER_DB_PASSWORD 与 COPPER_DB_PASSWORD_FILE 不能同时配置");
        }
        if (!hasText(fileValue)) return;

        Path secretFile = Path.of(fileValue.trim()).toAbsolutePath().normalize();
        if (!Path.of(fileValue.trim()).isAbsolute()) {
            throw new IllegalStateException("COPPER_DB_PASSWORD_FILE 必须是绝对路径");
        }
        String secret = readSecret(secretFile);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(PASSWORD_VALUE, secret);
        values.put(PASSWORD_SOURCE, "file");
        environment.getPropertySources().addFirst(new MapPropertySource("copperDockerSecrets", values));
    }

    private String readSecret(Path secretFile) {
        try {
            if (!Files.isRegularFile(secretFile) || !Files.isReadable(secretFile)) {
                throw new IllegalStateException("数据库密码文件不存在或不可读：" + secretFile);
            }
            String value = Files.readString(secretFile, StandardCharsets.UTF_8)
                    .replaceFirst("[\\r\\n]+$", "");
            if (value.isBlank()) throw new IllegalStateException("数据库密码文件不能为空");
            if (value.length() > 4096 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw new IllegalStateException("数据库密码文件格式不正确，只允许单行密码");
            }
            return value;
        } catch (IOException ex) {
            throw new IllegalStateException("数据库密码文件读取失败：" + secretFile, ex);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
