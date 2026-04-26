package com.securechat.service;

import com.securechat.entity.User;
import com.securechat.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service // Marks this as a Spring service bean
public class UserSyncService {

    private static final Logger logger = LoggerFactory.getLogger(UserSyncService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserSyncService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Get or create user from JWT token with proper race condition handling.
     * Uses the JWT subject (Keycloak user UUID) as the primary user ID.
     */
    @Transactional(value = jakarta.transaction.Transactional.TxType.REQUIRED)
    public User getOrCreateUser(Jwt jwt) {
        // Extract subject (Keycloak user UUID) from JWT token
        String keycloakSub = jwt.getSubject();
        if (keycloakSub == null || keycloakSub.trim().isEmpty()) {
            throw new IllegalArgumentException("JWT subject claim is null or empty");
        }

        // Convert Keycloak sub to UUID for use as user ID.
        // MUST be a valid UUID - Keycloak always issues UUID subjects.
        // If this fails, the token is invalid; we do NOT fall back to a random UUID
        // because that would silently recreate the UUID mismatch bug.
        UUID userId;
        try {
            userId = UUID.fromString(keycloakSub);
        } catch (IllegalArgumentException e) {
            logger.error("Keycloak JWT 'sub' claim is not a valid UUID: '{}'. Rejecting token.", keycloakSub);
            throw new IllegalArgumentException("Keycloak subject is not a valid UUID: " + keycloakSub, e);
        }

        // Fast-path: Try to find existing user by ID first
        Optional<User> existing = userRepository.findById(userId);
        if (existing.isPresent()) {
            logger.debug("User already exists for Keycloak sub: {}", keycloakSub);
            return existing.get();
        }

        // Extract email from JWT claims
        String emailClaim = jwt.getClaimAsString("email");

        // Extract preferred username from JWT
        String preferredUsername = jwt.getClaimAsString("preferred_username");

        // Prepare username and email
        // Use real Keycloak email if provided, otherwise generate unique synthetic
        // email
        String email = (emailClaim != null && !emailClaim.isBlank())
                ? emailClaim
                : generateUniqueEmail(userId);

        // Use preferred_username from JWT if available and unique, otherwise generate
        // unique username
        String username;
        if (preferredUsername != null && !preferredUsername.isBlank()) {
            // Check if preferred username is already taken
            Optional<User> existingByUsername = userRepository.findByUsername(preferredUsername);
            if (existingByUsername.isPresent()) {
                // Preferred username taken, generate unique variant
                logger.info("Preferred username '{}' already taken, generating unique variant", preferredUsername);
                username = generateUniqueUsernameWithPrefix(preferredUsername, userId);
            } else {
                username = preferredUsername;
            }
        } else {
            username = generateUniqueUsername(userId);
        }

        // Create new user
        User newUser = new User();
        newUser.setId(userId);
        newUser.setEmail(email);
        newUser.setUsername(username);
        newUser.setPasswordHash(passwordEncoder.encode("keycloak:" + keycloakSub));
        newUser.setIsActive(true);
        newUser.setCreatedAt(LocalDateTime.now());
        newUser.setLastLogin(LocalDateTime.now());
        newUser.setStatus(User.UserStatus.OFFLINE);

        // Set user roles based on JWT claims
        Set<User.UserRole> roles = new HashSet<>();
        roles.add(User.UserRole.ROLE_USER);

        // Add ADMIN role if JWT indicates admin privileges
        if (hasAdminRole(jwt)) {
            roles.add(User.UserRole.ROLE_ADMIN);
        }
        newUser.setRoles(roles);

        try {
            // Save new user
            User saved = userRepository.save(newUser);
            logger.info("Provisioned local user for Keycloak sub: {}", keycloakSub);
            return saved;

        } catch (DataIntegrityViolationException e) {
            // Race condition: another thread/instance inserted at same time
            logger.warn("Race condition detected during user creation for sub {}, attempting recovery: {}", keycloakSub,
                    e.getMessage());

            // Recovery attempt 1: lookup by ID
            Optional<User> recovered = userRepository.findById(userId);
            if (recovered.isPresent()) {
                logger.info("Successfully recovered user after race condition by id: {}", userId);
                return recovered.get();
            }

            // Recovery attempt 2: lookup by username
            recovered = userRepository.findByUsername(username);
            if (recovered.isPresent()) {
                logger.info("Recovered user by username after race condition: {}", username);
                return recovered.get();
            }

            // Recovery attempt 3: lookup by email
            recovered = userRepository.findByEmail(email);
            if (recovered.isPresent()) {
                logger.info("Recovered user by email after race condition: {}", email);
                return recovered.get();
            }

            // Unable to recover
            logger.error("Failed to recover user after race condition for sub {}", keycloakSub);
            throw new RuntimeException("Cannot recover user after race condition: " + e.getMessage(), e);
        }
    }

    /**
     * Generates a guaranteed unique username using loop-based verification.
     * This handles race conditions by continuously checking uniqueness until
     * success.
     */
    private String generateUniqueUsername(UUID userId) {
        String baseUsername = "user-" + userId;
        int attempt = 0;
        String username = baseUsername;

        // Try base username first, then numbered variants if conflicts exist
        while (userRepository.findByUsername(username).isPresent()) {
            attempt++;
            username = baseUsername + "-" + attempt;

            // Safety limit to prevent infinite loops
            if (attempt > 1000) {
                logger.error("Unable to generate unique username after 1000 attempts for userId: {}", userId);
                throw new RuntimeException("Cannot generate unique username for user: " + userId);
            }
        }

        logger.debug("Generated unique username for userId {}: {}", userId, username);
        return username;
    }

    /**
     * Generates a unique username based on a preferred prefix.
     * If the preferred username is taken, appends a number to make it unique.
     */
    private String generateUniqueUsernameWithPrefix(String preferredPrefix, UUID userId) {
        String baseUsername = preferredPrefix;
        int attempt = 0;
        String username = baseUsername;

        // Try base username first, then numbered variants if conflicts exist
        while (userRepository.findByUsername(username).isPresent()) {
            attempt++;
            username = baseUsername + "-" + attempt;

            // Safety limit to prevent infinite loops
            if (attempt > 1000) {
                logger.error("Unable to generate unique username from prefix '{}' after 1000 attempts",
                        preferredPrefix);
                // Fallback to UUID-based username
                return generateUniqueUsername(userId);
            }
        }

        logger.debug("Generated unique username from prefix '{}': {}", preferredPrefix, username);
        return username;
    }

    /**
     * Generates a guaranteed unique email address using loop-based verification.
     * This handles race conditions by continuously checking uniqueness until
     * success.
     */
    private String generateUniqueEmail(UUID userId) {
        String baseEmail = userId + "@keycloak.local";
        int attempt = 0;
        String email = baseEmail;

        // Try base email first, then numbered variants if conflicts exist
        while (userRepository.findByEmail(email).isPresent()) {
            attempt++;
            email = userId + "+" + attempt + "@keycloak.local";

            // Safety limit to prevent infinite loops
            if (attempt > 1000) {
                logger.error("Unable to generate unique email after 1000 attempts for userId: {}", userId);
                throw new RuntimeException("Cannot generate unique email for user: " + userId);
            }
        }

        logger.debug("Generated unique email for userId {}: {}", userId, email);
        return email;
    }

    /**
     * Checks if user has admin role in JWT claims
     */
    private boolean hasAdminRole(Jwt jwt) {
        if (hasRole(jwt, "admin") || hasRole(jwt, "ROLE_ADMIN") || hasRole(jwt, "ADMIN")) {
            return true;
        }

        return false;
    }

    /**
     * Generic role checker: looks for role in both realm_access and resource_access
     * claims
     */
    @SuppressWarnings("unchecked")
    private boolean hasRole(Jwt jwt, String role) {
        // Check realm_access.roles (Keycloak realm-level roles)
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
            if (roles.stream().anyMatch(r -> role.equalsIgnoreCase(String.valueOf(r)))) {
                return true;
            }
        }

        // Check resource_access.client.roles (Keycloak client-specific roles)
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            Object clientAccess = resourceAccess.get("securechat-backend");
            if (clientAccess instanceof Map<?, ?> clientMap) {
                Object clientRoles = clientMap.get("roles");
                if (clientRoles instanceof List<?> roles) {
                    return roles.stream().anyMatch(r -> role.equalsIgnoreCase(String.valueOf(r)));
                }
            }
        }

        return false;
    }

    /**
     * Sync user from OAuth2/OpenID Connect OidcUser object (browser session login).
     * UPSERT pattern: Always UPDATE user data from Keycloak, whether new or
     * existing.
     * This ensures the database always has the latest user information from
     * Keycloak.
     * 
     * @param oidcUser The OpenID Connect user from Spring Security
     * @return The synced or created user
     */
    @Transactional(value = jakarta.transaction.Transactional.TxType.REQUIRED)
    public User syncFromOidcUser(org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser) {
        if (oidcUser == null) {
            throw new IllegalArgumentException("OidcUser cannot be null");
        }

        // Extract Keycloak subject (UUID) from OidcUser
        String keycloakSub = oidcUser.getSubject();
        if (keycloakSub == null || keycloakSub.trim().isEmpty()) {
            throw new IllegalArgumentException("OidcUser subject is null or empty");
        }

        // Convert to UUID
        UUID userId;
        try {
            userId = UUID.fromString(keycloakSub);
        } catch (IllegalArgumentException e) {
            logger.error("OidcUser subject is not a valid UUID: '{}'. Rejecting.", keycloakSub);
            throw new IllegalArgumentException("OidcUser subject is not a valid UUID: " + keycloakSub, e);
        }

        // Extract email and username from OidcUser (Keycloak is source of truth)
        String email = oidcUser.getEmail();
        if (email == null || email.isBlank()) {
            email = generateUniqueEmail(userId);
        }

        String username = oidcUser.getPreferredUsername();
        if (username == null || username.isBlank()) {
            username = generateUniqueUsername(userId);
        } else {
            // Check if preferred_username is taken by ANOTHER user
            Optional<User> existingByUsername = userRepository.findByUsername(username);
            if (existingByUsername.isPresent() && !existingByUsername.get().getId().equals(userId)) {
                // Different user has this username, generate unique variant
                logger.info(
                        "OidcUser preferred_username '{}' already taken by different user, generating unique variant",
                        username);
                username = generateUniqueUsernameWithPrefix(username, userId);
            }
            // else: either no conflict or this user already has this username (OK to
            // update)
        }

        // 🔥 CRITICAL: UPSERT pattern - find or create
        User user = userRepository.findById(userId).orElse(new User());
        logger.debug("UPSERT Step 1 - Found or created user object. Existing: {}",
                user.getId() != null ? "YES" : "NO (new)");

        // Always UPDATE all fields from Keycloak (Keycloak is source of truth)
        user.setId(userId);
        user.setEmail(email);
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode("keycloak:" + keycloakSub));
        user.setIsActive(true);
        user.setLastLogin(LocalDateTime.now());
        if (user.getCreatedAt() == null) {
            user.setCreatedAt(LocalDateTime.now());
        }
        if (user.getStatus() == null) {
            user.setStatus(User.UserStatus.OFFLINE);
        }

        // Update user roles based on OidcUser claims
        Set<User.UserRole> roles = new HashSet<>();
        roles.add(User.UserRole.ROLE_USER);

        // Check for admin role in OidcUser
        if (hasAdminRoleInOidcUser(oidcUser)) {
            roles.add(User.UserRole.ROLE_ADMIN);
        }
        user.setRoles(roles);

        logger.debug("UPSERT Step 2 - User object prepared. ID={}, Email={}, Username={}, Roles={}",
                userId, email, username, roles);

        try {
            logger.info("🔄 UPSERT Step 3 - CALLING repository.save(user) NOW...");
            User saved = userRepository.save(user);
            logger.info("✅ UPSERT COMPLETE: UUID={}, email={}, username={}, roles={}",
                    userId, email, username, roles);
            logger.info("✅ SAVE RETURNED: {}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            logger.warn("Race condition during OidcUser UPSERT for subject {}: {}", keycloakSub, e.getMessage());

            // Recovery attempts
            Optional<User> recovered = userRepository.findById(userId);
            if (recovered.isPresent()) {
                logger.info("Recovered user by ID after race condition: {}", userId);
                return recovered.get();
            }

            recovered = userRepository.findByUsername(username);
            if (recovered.isPresent()) {
                logger.info("Recovered user by username after race condition: {}", username);
                return recovered.get();
            }

            recovered = userRepository.findByEmail(email);
            if (recovered.isPresent()) {
                logger.info("Recovered user by email after race condition: {}", email);
                return recovered.get();
            }

            logger.error("Failed to recover user after race condition for subject {}", keycloakSub);
            throw new RuntimeException("Cannot sync OidcUser after race condition: " + e.getMessage(), e);
        }
    }

    /**
     * Check if OidcUser has admin role
     */
    private boolean hasAdminRoleInOidcUser(org.springframework.security.oauth2.core.oidc.user.OidcUser oidcUser) {
        if (oidcUser == null) {
            return false;
        }

        // Check resource_access.securechat-backend.roles
        try {
            Map<String, Object> resourceAccess = oidcUser.getClaimAsMap("resource_access");
            if (resourceAccess != null) {
                Map<String, Object> clientRoles = (Map<String, Object>) resourceAccess.get("securechat-backend");
                if (clientRoles != null) {
                    List<String> roles = (List<String>) clientRoles.get("roles");
                    if (roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase("admin"))) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking admin role in OidcUser resource_access: {}", e.getMessage());
        }

        // Check realm_access.roles
        try {
            Map<String, Object> realmAccess = oidcUser.getClaimAsMap("realm_access");
            if (realmAccess != null) {
                List<String> roles = (List<String>) realmAccess.get("roles");
                if (roles != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase("admin"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking admin role in OidcUser realm_access: {}", e.getMessage());
        }

        return false;
    }
}
