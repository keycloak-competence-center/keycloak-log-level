package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;

import java.util.List;

/**
 * Port for reading and writing JVM-level logger state. The application layer
 * uses this to query and mutate categories without depending on a specific
 * LogManager implementation; infrastructure provides a concrete adapter (see
 * {@code JulLoggerRegistry}).
 */
public interface LoggerRegistry {

    /** All currently-registered logger names, including the unnamed root. */
    List<LoggerName> allNames();

    /**
     * Effective level — the level that's actually applied, walking up the
     * parent chain when this logger has no configured value. Implementations
     * should return a sensible default (typically {@code INFO}) if no anchor
     * is found.
     */
    String effectiveLevel(LoggerName name);

    /**
     * Explicit level set on this logger, or {@code null} when the logger
     * inherits from its parent / doesn't exist.
     */
    String configuredLevel(LoggerName name);

    /**
     * Apply {@code level} as the configured level for this logger. May lazily
     * create the logger if it doesn't exist yet — that's the expected behavior
     * for the synthetic-row affordance in the UI.
     *
     * @throws IllegalArgumentException if {@code level} cannot be parsed
     */
    void setLevel(LoggerName name, String level);

    /**
     * Clear the configured level so the logger inherits from its parent again.
     * No-op if the logger doesn't exist.
     */
    void clearLevel(LoggerName name);
}
