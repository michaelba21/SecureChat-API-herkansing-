package com.securechat.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {


    @Override
    public void run(String... args) {
        // Admin user creation removed to prevent UUID mismatch with Keycloak.
        // Users should only be provisioned via Keycloak UserSyncService.
    }
}