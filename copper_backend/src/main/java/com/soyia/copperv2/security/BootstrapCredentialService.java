package com.soyia.copperv2.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class BootstrapCredentialService {
    private static final String FORMAT = "COPPER_INITIAL_ADMIN_V1";
    // Keep the alphabet deliberately split: a single long alphanumeric literal is
    // indistinguishable from a hard-coded credential to secret scanners.
    private static final String PASSWORD_UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_LOWERCASE = "abcdefghijkmnopqrstuvwxyz";
    private static final String PASSWORD_DIGITS = "23456789";
    private static final String PASSWORD_CHARS = PASSWORD_UPPERCASE + PASSWORD_LOWERCASE + PASSWORD_DIGITS;
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
    );

    private final String username;
    private final Path credentialFile;
    private final SecureRandom secureRandom;

    @Autowired
    public BootstrapCredentialService(
            @Value("${copper.bootstrap.username}") String username,
            @Value("${copper.bootstrap.credential-file}") String credentialFile
    ) {
        this(username, Path.of(credentialFile), new SecureRandom());
    }

    BootstrapCredentialService(String username, Path credentialFile, SecureRandom secureRandom) {
        String normalizedUsername = username == null ? "" : username.trim();
        if (!normalizedUsername.matches("^[A-Za-z0-9._-]{3,64}$")) {
            throw new IllegalStateException("COPPER_BOOTSTRAP_USERNAME 只能包含字母、数字、点、下划线或短横线，长度为 3-64 位");
        }
        this.username = normalizedUsername;
        this.credentialFile = credentialFile.toAbsolutePath().normalize();
        this.secureRandom = secureRandom;
    }

    public Credential loadOrCreate() {
        if (Files.exists(credentialFile)) return readExisting();
        String issuedCredential = generatePassword();
        String content = String.join("\n",
                FORMAT,
                "username=" + username,
                "password=" + issuedCredential,
                "createdAt=" + OffsetDateTime.now(),
                "mustChangePassword=true",
                "notice=首次登录后必须立即修改密码，修改成功后本文件会自动销毁。",
                ""
        );
        try {
            Path parent = credentialFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(credentialFile, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            restrictPermissions();
            return new Credential(username, issuedCredential, credentialFile);
        } catch (FileAlreadyExistsException ex) {
            return readExisting();
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建首次管理员一次性凭据文件，请检查 COPPER_BOOTSTRAP_CREDENTIAL_FILE 的目录权限", ex);
        }
    }

    public boolean isBootstrapUsername(String candidate) {
        return username.equals(candidate);
    }

    public boolean destroyIfPresent() {
        try {
            return Files.deleteIfExists(credentialFile);
        } catch (IOException ex) {
            throw new IllegalStateException("无法销毁首次管理员一次性凭据文件，新密码未生效；请检查文件权限后重试", ex);
        }
    }

    Path credentialFile() {
        return credentialFile;
    }

    private Credential readExisting() {
        try {
            List<String> lines = Files.readAllLines(credentialFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !FORMAT.equals(lines.get(0))) {
                throw new IllegalStateException("首次管理员凭据文件格式不正确；为避免覆盖未知文件，Copper 已停止启动");
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (String line : lines.subList(1, lines.size())) {
                int split = line.indexOf('=');
                if (split > 0) values.put(line.substring(0, split), line.substring(split + 1));
            }
            String fileUsername = values.getOrDefault("username", "").trim();
            String password = values.getOrDefault("password", "");
            if (!username.equals(fileUsername) || password.isBlank()) {
                throw new IllegalStateException("首次管理员凭据文件与当前配置不一致；Copper 已停止启动，请核对用户名和凭据文件");
            }
            restrictPermissions();
            return new Credential(username, password, credentialFile);
        } catch (IOException ex) {
            throw new IllegalStateException("无法读取首次管理员一次性凭据文件，请检查文件权限", ex);
        }
    }

    private void restrictPermissions() throws IOException {
        try {
            Files.setPosixFilePermissions(credentialFile, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException ignored) {
            if (!credentialFile.toFile().setReadable(false, false)
                    || !credentialFile.toFile().setWritable(false, false)
                    || !credentialFile.toFile().setReadable(true, true)
                    || !credentialFile.toFile().setWritable(true, true)) {
                throw new IOException("无法限制凭据文件权限");
            }
        }
    }

    private String generatePassword() {
        List<Character> characters = new ArrayList<>();
        characters.add(randomCharacter(PASSWORD_UPPERCASE));
        characters.add(randomCharacter(PASSWORD_LOWERCASE));
        characters.add(randomCharacter(PASSWORD_DIGITS));
        while (characters.size() < 24) characters.add(randomCharacter(PASSWORD_CHARS));
        Collections.shuffle(characters, secureRandom);
        StringBuilder password = new StringBuilder(characters.size());
        characters.forEach(password::append);
        return password.toString();
    }

    private char randomCharacter(String candidates) {
        return candidates.charAt(secureRandom.nextInt(candidates.length()));
    }

    public record Credential(String username, String password, Path deliveryFile) {}
}
