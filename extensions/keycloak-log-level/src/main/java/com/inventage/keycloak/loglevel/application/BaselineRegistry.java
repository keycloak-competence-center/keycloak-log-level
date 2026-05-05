package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;

/**
 * Port for the per-logger startup baseline — the level captured when Keycloak
 * finished booting, before any UI-driven changes. Used by reset operations so
 * a logger pinned via {@code KC_LOG_LEVEL=...:debug} returns to {@code DEBUG}
 * (not to inherited).
 *
 * <p>Lifecycle of population is the adapter's concern; the application only
 * needs to look up.
 */
public interface BaselineRegistry {

    /** Captured startup level for {@code name}, or {@code null} if none recorded. */
    String get(LoggerName name);
}
