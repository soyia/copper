package com.soyia.copperv2.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapCredentialServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void createsOwnerOnlyCredentialAndReusesItUntilDestroyed() throws Exception {
        Path credentialFile = tempDir.resolve("secrets/initial-admin.txt");
        BootstrapCredentialService service = new BootstrapCredentialService("admin", credentialFile, new SecureRandom());

        BootstrapCredentialService.Credential first = service.loadOrCreate();
        BootstrapCredentialService.Credential second = service.loadOrCreate();

        assertEquals("admin", first.username());
        assertEquals(first.password(), second.password(), "restart before password change must keep the delivered credential valid");
        assertEquals(24, first.password().length());
        assertTrue(first.password().matches(".*[A-Za-z].*"));
        assertTrue(first.password().matches(".*\\d.*"));
        assertTrue(Files.readString(credentialFile).startsWith("COPPER_INITIAL_ADMIN_V1\n"));
        assertEquals(EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(credentialFile));

        assertTrue(service.destroyIfPresent());
        assertFalse(Files.exists(credentialFile));
        assertFalse(service.destroyIfPresent());
    }

    @Test
    void refusesToOverwriteUnknownCredentialFile() throws Exception {
        Path credentialFile = tempDir.resolve("initial-admin.txt");
        Files.writeString(credentialFile, "unrelated content", StandardCharsets.UTF_8);
        BootstrapCredentialService service = new BootstrapCredentialService("admin", credentialFile, new SecureRandom());

        IllegalStateException error = assertThrows(IllegalStateException.class, service::loadOrCreate);

        assertTrue(error.getMessage().contains("不会覆盖") || error.getMessage().contains("避免覆盖"));
        assertEquals("unrelated content", Files.readString(credentialFile));
    }
}
