package com.soyia.copperv2.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SecurityAuditServiceTest {
    @AfterEach
    void clearContexts() {
        SecurityContext.clear();
        SecurityRequestContext.clear();
    }

    @Test
    void changeSnapshotRedactsPasswordsTokensCookiesAndCredentials() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SecurityAuditService service = new SecurityAuditService(jdbc, new ObjectMapper());
        SecurityContext.set(new SecurityContext.Principal(
                9L, 1L, 2L, "管理员", "000001", 1L, "本企业", "admin",
                false, List.of("管理员"), Set.of("user.manage")
        ));

        service.successChange("UPDATE_USER", "USER", 3L, "", "修改授权",
                Map.of("password", "plain-secret", "nested", Map.of("tokenValue", "raw-token")),
                Map.of("cookie", "raw-cookie", "credentialFile", "/secret/path"));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), arguments.capture());
        String before = String.valueOf(arguments.getValue()[11]);
        String after = String.valueOf(arguments.getValue()[12]);
        assertFalse(before.contains("plain-secret"));
        assertFalse(before.contains("raw-token"));
        assertFalse(after.contains("raw-cookie"));
        assertFalse(after.contains("/secret/path"));
        assertTrue(before.contains("[REDACTED]"));
        assertTrue(after.contains("[REDACTED]"));
    }
}
