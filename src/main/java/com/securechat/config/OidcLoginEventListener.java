package com.securechat.config;

import com.securechat.service.UserSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Listens for successful authentication events in Spring Security.
 * This completely decouples the sync logic from SecurityConfig,
 * avoiding circular dependencies.
 */
@Component
public class OidcLoginEventListener {

    private static final Logger logger = LoggerFactory.getLogger(OidcLoginEventListener.class);

    private final UserSyncService userSyncService;

    // Injects UserSyncService. Because this is just a regular @Component listener,
    // it doesn't cause a dependency cycle with SecurityConfig.
    public OidcLoginEventListener(UserSyncService userSyncService) {
        this.userSyncService = userSyncService;
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        // We only care about OAuth2 browser logins, which produce an OidcUser
        if (event.getAuthentication().getPrincipal() instanceof OidcUser oidcUser) {
            try {
                logger.info("[OIDC-SYNC] Login success event for sub={}, email={} — syncing to DB...",
                        oidcUser.getSubject(), oidcUser.getEmail());

                userSyncService.syncFromOidcUser(oidcUser);

                logger.info("[OIDC-SYNC] ✅ User synced to DB successfully: sub={}", oidcUser.getSubject());
            } catch (Exception ex) {
                // Log but do not crash the authentication process
                logger.error("[OIDC-SYNC] ❌ Failed to sync user to DB for sub={}: {}",
                        oidcUser.getSubject(), ex.getMessage(), ex);
            }
        }
    }
}
