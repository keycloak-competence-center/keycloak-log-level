package com.inventage.keycloak.loglevel.infrastructure;

import org.keycloak.cluster.ClusterEvent;

/**
 * Wire format for the cluster broadcast — the payload that {@link KeycloakClusterBroadcaster}
 * sends and that the receiving listener (registered in {@link LoggingResourceProviderFactory})
 * decodes to apply on the local LogManager.
 *
 * <p>An infrastructure type — implements Keycloak's {@link ClusterEvent}
 * because the cluster SPI requires it — but the application layer never sees
 * this class.
 *
 * @param loggerName  unwrapped logger name (root logger is the empty string)
 * @param level       target level name; {@code null} signals a reset (logger
 *                    falls back to its baseline or inherits from its parent)
 * @param triggeredBy username of the admin who triggered the change — surfaced
 *                    in receivers' logs only
 */
public record LogLevelChangeEvent(String loggerName, String level, String triggeredBy)
        implements ClusterEvent {

    /** Event for a {@code PUT} that applies an explicit level to {@code loggerName}. */
    public static LogLevelChangeEvent set(String loggerName, String level, String triggeredBy) {
        return new LogLevelChangeEvent(loggerName, level, triggeredBy);
    }

    /** Event for a {@code DELETE} that restores {@code loggerName} to its startup baseline. */
    public static LogLevelChangeEvent reset(String loggerName, String triggeredBy) {
        return new LogLevelChangeEvent(loggerName, null, triggeredBy);
    }

    /** {@code true} when this event was produced by {@link #reset}. */
    public boolean isReset() {
        return level == null;
    }
}
