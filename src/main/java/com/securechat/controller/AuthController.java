package com.securechat.controller;

import com.securechat.dto.AuthRequest;
import com.securechat.dto.AuthResponse;
import com.securechat.dto.LoginRequest;
import com.securechat.service.AuthService;
import com.securechat.service.UserSyncService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

@RestController
@RequestMapping("/api/auth") // Base path for authentication endpoints
@CrossOrigin(origins = "*") // For local development testing - allow all origins
public class AuthController {

    @Autowired
    private AuthService authService; // Inject authentication service

    @Autowired
    private UserSyncService userSyncService; // Inject user sync service for persisting users

    /*
     * LOCAL REGISTRATION & LOGIN DISABLED
     * 
     * Switch to Keycloak-only authentication for single source of truth.
     * All users must now register/login via Keycloak to ensure UUID consistency
     * and prevent 401/403 errors from UUID mismatches.
     */

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
                AuthResponse.error("  Local login disabled. Please use Keycloak OAuth2 flow. " +
                        "Token endpoint: http://localhost:9090/realms/SecureChat/protocol/openid-connect/token"));
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody AuthRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
                AuthResponse.error("  Local registration disabled. Please register via Keycloak admin console. " +
                        "Admin URL: http://localhost:9090/admin/master/console/"));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@RequestBody java.util.Map<String, String> payload,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(
                AuthResponse.error(
                        "  Keycloak handles token refresh. Use Keycloak token endpoint with refresh_token grant type."));
    }

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(AuthController.class);

    /**
     * Check authentication status - useful for debugging and verification.
     * Works with BOTH OAuth2 session login AND JWT Bearer tokens.
     * 
     * CRITICAL: For session login (OidcUser), triggers UserSyncService to persist user to database.
     * This ensures the user UUID from Keycloak is stored in the database.
     * 
     * Flow:
     * 1. Browser login (OAuth2) → OidcUser created, user synced to DB, session established
     * 2. API call with Bearer token → JWT created, role extraction works
     */
    @GetMapping("/status")
    public ResponseEntity<java.util.Map<String, Object>> authStatus(
            @org.springframework.security.core.annotation.AuthenticationPrincipal org.springframework.security.oauth2.jwt.Jwt jwt,
            @org.springframework.security.core.annotation.AuthenticationPrincipal OidcUser oidcUser,
            org.springframework.security.core.Authentication auth) {

        log.debug("Auth object: {}, JWT: {}, OidcUser: {}", 
            auth != null ? auth.getClass().getSimpleName() : "null",
            jwt != null ? "present" : "null",
            oidcUser != null ? "present" : "null");
        
        // Case 1: Not authenticated at all
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("User not authenticated. Auth: {}", auth);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(java.util.Map.of(
                    "authenticated", false,
                    "message", "Not authenticated. Please login via Keycloak",
                    "keycloakUrl", "http://localhost:9090/realms/SecureChat/protocol/openid-connect/auth"));
        }

        // Case 2: OAuth2 session login (browser) - OidcUser present
        if (oidcUser != null) {
            log.info(" OAuth2 Session detected. Email: {}, Subject: {}", oidcUser.getEmail(), oidcUser.getSubject());
            try {
                //  CRITICAL: Sync user to database on first login
                log.info(" CALLING userSyncService.syncFromOidcUser() NOW...");
                var syncedUser = userSyncService.syncFromOidcUser(oidcUser);
                log.info(" User synced successfully. Synced user ID: {}, Email: {}", syncedUser.getId(), syncedUser.getEmail());
            } catch (Exception e) {
                log.error(" FAILED to sync OidcUser to database: {} - {}", e.getClass().getSimpleName(), e.getMessage(), e);
                // Continue anyway - user is authenticated even if sync failed
            }

            return ResponseEntity.ok(java.util.Map.ofEntries(
                    java.util.Map.entry("authenticated", true),
                    java.util.Map.entry("authType", "SESSION"),
                    java.util.Map.entry("userId", oidcUser.getSubject()),
                    java.util.Map.entry("email", oidcUser.getEmail()),
                    java.util.Map.entry("username", oidcUser.getPreferredUsername()),
                    java.util.Map.entry("roles", auth.getAuthorities().stream()
                            .map(Object::toString)
                            .toList()),
                    java.util.Map.entry("message", " Authenticated via OAuth2 session + synced to DB")));
        }

        // Case 3: JWT Bearer token - use Jwt object
        if (jwt != null) {
            log.info("JWT-based authentication detected for user: {}", jwt.getSubject());
            return ResponseEntity.ok(java.util.Map.ofEntries(
                    java.util.Map.entry("authenticated", true),
                    java.util.Map.entry("authType", "JWT"),
                    java.util.Map.entry("userId", jwt.getSubject()),
                    java.util.Map.entry("username", jwt.getClaimAsString("preferred_username")),
                    java.util.Map.entry("email", jwt.getClaimAsString("email")),
                    java.util.Map.entry("roles", jwt.getClaimAsStringList("roles")),
                    java.util.Map.entry("expiresAt", jwt.getExpiresAt()),
                    java.util.Map.entry("message", " Authenticated via JWT token")));
        }

        // Fallback: authenticated but no JWT or OidcUser (unusual case)
        log.warn("Authenticated but no JWT or OidcUser available. Auth type: {}", auth.getClass().getSimpleName());
        return ResponseEntity.ok(java.util.Map.ofEntries(
                java.util.Map.entry("authenticated", true),
                java.util.Map.entry("authType", "UNKNOWN"),
                java.util.Map.entry("username", auth.getName()),
                java.util.Map.entry("roles", auth.getAuthorities().stream()
                        .map(Object::toString)
                        .toList()),
                java.util.Map.entry("message", " Authenticated (fallback case)")));
    }

    private String getClientIp(HttpServletRequest request) {
        String header = request.getHeader("X-Forwarded-For"); // Check proxy header first
        if (header != null && !header.isBlank()) {
            return header.split(",")[0].trim(); // Use first IP in chain (client's original IP)
        }
        return request.getRemoteAddr(); // Fallback to direct connection IP
    }

    private String getUserAgent(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        // Some clients (e.g., PowerShell Invoke-RestMethod) may omit User-Agent.
        // Keep refresh_tokens.user_agent non-null by providing a safe default.
        return (userAgent == null || userAgent.isBlank()) ? "unknown" : userAgent; // Default to "unknown" if missing
    }
}