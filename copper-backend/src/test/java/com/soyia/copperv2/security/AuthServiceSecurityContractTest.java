package com.soyia.copperv2.security;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthServiceSecurityContractTest {
    @Test
    void loginFailureIsCommittedSoLockoutAndAuditCannotRollback() throws Exception {
        Method login = AuthService.class.getMethod("login", String.class, String.class, String.class, String.class);
        Transactional transactional = login.getAnnotation(Transactional.class);
        assertNotNull(transactional);
        assertTrue(Arrays.asList(transactional.noRollbackFor()).contains(UnauthorizedException.class));
    }

    @Test
    void identifiersAreHashedCaseInsensitivelyWithoutReturningPlaintext() {
        String first = SecurityAuditService.hashIdentifier(" Admin ");
        String second = SecurityAuditService.hashIdentifier("admin");
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertTrue(first.equals(second));
        assertTrue(!first.contains("admin"));
    }
}
