package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.LoggerRegistry;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

import static com.inventage.keycloak.loglevel.domain.LoggerName.ROOT;

/**
 * Composition root for the keycloak-log-level extension. Owns the JVM-scoped
 * adapters ({@link JulLoggerRegistry} and {@link StartupBaselineRegistry}),
 * snapshots the startup baseline once Keycloak is ready, and registers a
 * cluster listener so this node applies level changes broadcast by other
 * replicas. Per-request adapters (the cluster broadcaster and the audit
 * publisher) are wired by the REST resource itself so they can carry the
 * request's session and auth context.
 */
public class LoggingResourceProviderFactory implements RealmResourceProviderFactory {

    private static final Logger LOG = Logger.getLogger(LoggingResourceProviderFactory.class);

    public static final String ID = "logging";

    private final LoggerRegistry loggers = new JulLoggerRegistry();
    private final StartupBaselineRegistry baselines = new StartupBaselineRegistry();

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new LoggingResourceProvider(session, loggers, baselines);
    }

    @Override
    public void init(Config.Scope config) {
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        baselines.snapshot();
        registerClusterListener(factory);
    }

    private void registerClusterListener(KeycloakSessionFactory factory) {
        if (factory == null) {
            // unit tests pass null; no cluster wiring there
            return;
        }
        final KeycloakSession session = factory.create();
        try {
            final ClusterProvider cluster = session.getProvider(ClusterProvider.class);
            if (cluster == null) {
                LOG.warn("ClusterProvider unavailable; log level changes will not propagate to other replicas");
                return;
            }
            cluster.registerListener(KeycloakClusterBroadcaster.CLUSTER_TASK_KEY, this::onClusterEvent);
        } finally {
            session.close();
        }
    }

    /**
     * Apply a {@link LogLevelChangeEvent} received from another node. The
     * originating node has already mutated its own LogManager; this code path
     * is only reached on receivers ({@code notify(..., ignoreSender=true, ...)}).
     *
     * <p>For reset events, the receiver applies <em>its own</em> recorded
     * baseline. In a homogeneous cluster (all replicas booted with the same
     * KC_LOG_LEVEL / Quarkus config) baselines match across nodes; in scale-up
     * scenarios where a node joined after a UI change, baselines can diverge
     * for non-config-driven loggers — that's an acceptable corner case for a
     * diagnostic feature.
     */
    private void onClusterEvent(ClusterEvent event) {
        if (!(event instanceof LogLevelChangeEvent change)) {
            return;
        }
        final LoggerName target = change.loggerName().isEmpty()
                ? ROOT
                : new LoggerName(change.loggerName());
        if (change.isReset()) {
            final String baseline = baselines.get(target);
            if (baseline == null) {
                loggers.clearLevel(target);
            } else {
                loggers.setLevel(target, baseline);
            }
            LOG.infof("Cluster: log level for '%s' reset (originator: %s)",
                    target.display(), change.triggeredBy());
        } else {
            try {
                loggers.setLevel(target, change.level());
                LOG.infof("Cluster: log level for '%s' set to %s (originator: %s)",
                        target.display(), change.level(), change.triggeredBy());
            }
            catch (IllegalArgumentException e) {
                // The originating node validated the level before broadcasting,
                // so this should never fire — log defensively rather than crash.
                LOG.warnf("Cluster: ignored invalid level '%s' for logger '%s'",
                        change.level(), target.display());
            }
        }
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return ID;
    }

    /** Visible for tests — clears the baseline snapshot so a unit test can re-snapshot a known state. */
    public StartupBaselineRegistry baselineRegistry() {
        return baselines;
    }
}
