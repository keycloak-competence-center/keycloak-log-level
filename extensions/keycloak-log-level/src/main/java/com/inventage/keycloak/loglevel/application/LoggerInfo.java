package com.inventage.keycloak.loglevel.application;

/**
 * Snapshot of a single logger's level state. Returned from {@link LogLevelService}
 * to the REST adapter, which serializes it as the response body for {@code GET /}
 * and {@code GET /{logger}}.
 *
 * @param level           effective level — what's actually applied, walking up
 *                        the parent chain when the logger has no configured value
 * @param configuredLevel level explicitly set on the logger itself, or {@code null}
 *                        if it inherits
 * @param baselineLevel   level captured at startup, or {@code null} if the logger
 *                        was inheriting at that point or didn't yet exist
 */
public record LoggerInfo(String level, String configuredLevel, String baselineLevel) {
}
