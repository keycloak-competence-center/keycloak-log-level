package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.ClusterBroadcaster;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.models.KeycloakSession;

/**
 * Adapter implementing {@link ClusterBroadcaster} on top of Keycloak's
 * {@link ClusterProvider}. {@code ignoreSender=true} skips the originator,
 * which has already mutated its own LogManager directly.
 *
 * <p>Failures here are logged but not propagated to the caller — the local
 * level change has already succeeded and we don't want to fail the response
 * because cluster broadcast hit a transient issue.
 */
public class KeycloakClusterBroadcaster implements ClusterBroadcaster {

    private static final Logger LOG = Logger.getLogger(KeycloakClusterBroadcaster.class);

    /**
     * Task key under which {@link LogLevelChangeEvent}s are broadcast through
     * Keycloak's {@link ClusterProvider}. Listeners on every node register
     * under this key and apply received level changes locally.
     */
    public static final String CLUSTER_TASK_KEY = "kc-log-level:level-changed";

    private final KeycloakSession session;

    public KeycloakClusterBroadcaster(KeycloakSession session) {
        this.session = session;
    }

    @Override
    public void broadcastSet(LoggerName name, String level, String triggeredBy) {
        broadcast(LogLevelChangeEvent.set(name.value(), level, triggeredBy));
    }

    @Override
    public void broadcastReset(LoggerName name, String triggeredBy) {
        broadcast(LogLevelChangeEvent.reset(name.value(), triggeredBy));
    }

    private void broadcast(LogLevelChangeEvent event) {
        try {
            final ClusterProvider cluster = session.getProvider(ClusterProvider.class);
            if (cluster == null) {
                return;
            }
            cluster.notify(CLUSTER_TASK_KEY, event, true);
        }
        catch (RuntimeException e) {
            LOG.warnf(e, "Failed to broadcast log level change for '%s'", event.loggerName());
        }
    }
}
