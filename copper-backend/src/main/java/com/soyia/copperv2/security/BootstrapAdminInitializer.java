package com.soyia.copperv2.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminInitializer {
    private final AuthService authService;

    public BootstrapAdminInitializer(AuthService authService) {
        this.authService = authService;
    }

    @PostConstruct
    public void initialize() {
        authService.ensureBootstrapAccount();
    }
}
