package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;

/**
 * Port for emitting audit events recording log-level changes. Implementations
 * should be best-effort — a failed audit emit must not fail the originating
 * request, since the local change has already taken effect.
 */
public interface AuditPublisher {

    void publishSet(LoggerName name, String level);

    /**
     * @param postResetLevel the level the logger ends up at after the reset —
     *                       the captured baseline, or {@code null} when reset
     *                       cleared the configured level (no baseline existed)
     */
    void publishReset(LoggerName name, String postResetLevel);
}
