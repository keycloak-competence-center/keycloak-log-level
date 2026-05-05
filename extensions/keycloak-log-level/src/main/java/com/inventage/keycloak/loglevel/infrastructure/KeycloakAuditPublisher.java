package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.AuditPublisher;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import org.jboss.logging.Logger;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.keycloak.services.resources.admin.AdminAuth;
import org.keycloak.services.resources.admin.AdminEventBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.keycloak.events.admin.OperationType.DELETE;
import static org.keycloak.events.admin.OperationType.UPDATE;
import static org.keycloak.events.admin.ResourceType.CUSTOM;

/**
 * Adapter implementing {@link AuditPublisher} via Keycloak's
 * {@link AdminEventBuilder}. Surfaces log-level changes in the master realm's
 * admin-events log — what audit tooling typically scrapes — so a level toggle
 * that changes what gets logged is itself logged.
 *
 * <p>Per-request scope: holds the {@link AuthResult} so it can construct the
 * {@link AdminAuth} the builder requires. Failures are caught and logged but
 * do not propagate; if the realm has admin events disabled the emit silently
 * no-ops, which is the expected behavior.
 */
public class KeycloakAuditPublisher implements AuditPublisher {

    private static final Logger LOG = Logger.getLogger(KeycloakAuditPublisher.class);

    private final KeycloakSession session;
    private final AuthResult auth;

    public KeycloakAuditPublisher(KeycloakSession session, AuthResult auth) {
        this.session = session;
        this.auth = auth;
    }

    @Override
    public void publishSet(LoggerName name, String level) {
        publish(UPDATE, name, Map.of("level", level));
    }

    @Override
    public void publishReset(LoggerName name, String postResetLevel) {
        // Carry the post-reset target in the audit event so an auditor can tell
        // whether the logger ended up inheriting (null) or pinned at a baseline.
        final Map<String, String> repr = new LinkedHashMap<>();
        repr.put("baseline", postResetLevel);
        publish(DELETE, name, repr);
    }

    private void publish(OperationType operation, LoggerName name, Object representation) {
        try {
            final RealmModel realm = session.getContext().getRealm();
            final AdminAuth adminAuth = new AdminAuth(realm, auth.token(), auth.user(), auth.client());
            new AdminEventBuilder(realm, adminAuth, session, session.getContext().getConnection())
                    .resource(CUSTOM)
                    .operation(operation)
                    .resourcePath("logging", name.display())
                    .representation(representation)
                    .success();
        }
        catch (RuntimeException e) {
            LOG.warnf(e, "Failed to emit admin event for log-level %s on '%s'", operation, name.value());
        }
    }
}
