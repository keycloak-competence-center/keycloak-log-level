package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.BaselineRegistry;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * In-memory {@link BaselineRegistry} populated once at Keycloak post-init —
 * after Quarkus has applied {@code KC_LOG_LEVEL} / {@code quarkus.log.category.*.level}
 * to the {@link LogManager}, but before any UI-driven changes are possible.
 *
 * <p>Only loggers with a non-null configured level at startup get an entry —
 * the absence of a name encodes "no baseline → reset clears the configured
 * level" without needing nullable map values (which {@link ConcurrentHashMap}
 * forbids). Keyed by logger name; the unnamed root logger uses {@link LoggerName#ROOT}.
 */
public class StartupBaselineRegistry implements BaselineRegistry {

    private static final String QUARKUS_LOG_CATEGORY_PREFIX = "quarkus.log.category.\"";
    private static final String QUARKUS_LOG_LEVEL_SUFFIX = "\".level";

    private final Map<LoggerName, String> baselines = new ConcurrentHashMap<>();

    @Override
    public String get(LoggerName name) {
        return baselines.get(name);
    }

    /**
     * Capture the current LogManager state as the baseline. Called once during
     * SPI {@code postInit}, after Quarkus has finished wiring up category levels.
     *
     * <p>Two passes:
     * <ol>
     *     <li>Walk every logger already in the {@link LogManager} — catches the
     *     Quarkus internals (Hibernate, Agroal, OpenTelemetry, …) that are
     *     eagerly created during boot.</li>
     *     <li>Walk MicroProfile Config for {@code quarkus.log.category."<name>".level}
     *     keys and force-create those loggers. Quarkus configures these
     *     categories lazily — the level is only attached when something asks
     *     for the Logger — so without this step a category set via
     *     {@code KC_LOG_LEVEL=...:debug} would have no baseline recorded, and
     *     reset would silently fall back to "clear" instead of restoring the
     *     configured value.</li>
     * </ol>
     */
    public void snapshot() {
        final LogManager mgr = LogManager.getLogManager();
        mgr.getLoggerNames().asIterator().forEachRemaining(this::captureBaseline);
        // The unnamed root logger is sometimes absent from getLoggerNames()
        // depending on the LogManager implementation — capture it explicitly.
        captureBaseline("");
        captureQuarkusCategoryBaselines();
    }

    private void captureQuarkusCategoryBaselines() {
        try {
            for (final String key : ConfigProvider.getConfig().getPropertyNames()) {
                if (key.startsWith(QUARKUS_LOG_CATEGORY_PREFIX) && key.endsWith(QUARKUS_LOG_LEVEL_SUFFIX)) {
                    final String name = key.substring(
                            QUARKUS_LOG_CATEGORY_PREFIX.length(),
                            key.length() - QUARKUS_LOG_LEVEL_SUFFIX.length());
                    // Force creation. Logger.getLogger triggers the LogManager's
                    // category resolution which applies the configured level.
                    Logger.getLogger(name);
                    captureBaseline(name);
                }
            }
        }
        catch (Throwable ignored) {
            // MicroProfile Config not on the classpath (unit tests outside Keycloak)
            // or unavailable for any other reason — best-effort. The LogManager-based
            // pass above already covers the common case.
        }
    }

    private void captureBaseline(String name) {
        final Logger logger = LogManager.getLogManager().getLogger(name);
        if (logger == null) {
            return;
        }
        final Level level = logger.getLevel();
        // Skip loggers inheriting at startup; absence from the map encodes "no
        // baseline → reset clears the configured level".
        if (level == null) {
            return;
        }
        // putIfAbsent so a hot-reload (re-init in dev mode) doesn't overwrite
        // a baseline with a level that may already have been mutated by the UI.
        baselines.putIfAbsent(
                name.isEmpty() ? LoggerName.ROOT : new LoggerName(name),
                level.getName());
    }

    /** Visible for tests — clears the snapshot so a unit test can re-snapshot a known state. */
    public void clearForTesting() {
        baselines.clear();
    }
}
