package com.soyia.copperv2.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceBootstrapPasswordTest {
    @AfterEach
    void clearContext() {
        SecurityContext.clear();
    }

    @Test
    void firstPasswordChangeDestroysCredentialAndAllTemporarySessions() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PasswordHasher passwordHasher = new PasswordHasher();
        BootstrapCredentialService credentials = mock(BootstrapCredentialService.class);
        SecurityAuditService auditService = mock(SecurityAuditService.class);
        String currentHash = passwordHasher.hash("Temporary123");
        when(jdbc.queryForMap(anyString(), eq(1L))).thenReturn(Map.of(
                "username", "admin",
                "password_hash", currentHash,
                "password_change_required", 1
        ));
        when(credentials.isBootstrapUsername("admin")).thenReturn(true);
        when(credentials.destroyIfPresent()).thenReturn(true);
        SecurityContext.set(new SecurityContext.Principal(
                1L, 1L, "系统管理员", "000001", 1L, "本企业", "admin",
                true, List.of("系统管理员"), Set.of()
        ));

        new AuthService(jdbc, passwordHasher, credentials, auditService, 12, 5)
                .changePassword(1L, "Temporary123", "Permanent456", "127.0.0.1");

        verify(jdbc).update(contains("UPDATE system_user_session"), eq("PASSWORD_CHANGED"), eq(1L), eq(1L));
        verify(credentials).destroyIfPresent();
        verify(jdbc, atLeastOnce()).update(anyString(), any(Object[].class));
    }
}
