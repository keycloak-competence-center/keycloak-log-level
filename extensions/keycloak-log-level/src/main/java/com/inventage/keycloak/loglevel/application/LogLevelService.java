package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static java.util.Objects.requireNonNull;

/**
 * Application service orchestrating the log-level use cases: list, get, set,
 * reset. Depends only on application-layer ports — concrete adapters live in
 * the infrastructure layer and are wired in by the SPI factory at request time.
 *
 * <p>None of the operations validate authorization or HTTP-shape concerns —
 * that's the REST adapter's job. The service runs on the assumption that the
 * caller is authorized.
 */
public class LogLevelService {

    private final LoggerRegistry loggers;
    private final BaselineRegistry baselines;
    private final ClusterBroadcaster broadcaster;
    private final AuditPublisher audit;

    public LogLevelService(LoggerRegistry loggers,
                           BaselineRegistry baselines,
                           ClusterBroadcaster broadcaster,
                           AuditPublisher audit) {
        this.loggers = requireNonNull(loggers);
        this.baselines = requireNonNull(baselines);
        this.broadcaster = requireNonNull(broadcaster);
        this.audit = requireNonNull(audit);
    }

    /**
     * All registered loggers, ordered by name. Returns a sorted map so the
     * REST layer's JSON output is deterministic for operators scrolling
     * through a long list.
     */
    public Map<String, LoggerInfo> list() {
        final Map<String, LoggerInfo> result = new TreeMap<>();
        for (final LoggerName name : loggers.allNames()) {
            result.put(name.display(), snapshot(name));
        }
        return result;
    }

    /** Single logger lookup, including the {@code logger} key for symmetry with {@link #list()}. */
    public Map<String, String> get(LoggerName name) {
        final LoggerInfo snap = snapshot(name);
        final Map<String, String> result = new LinkedHashMap<>();
        result.put("logger", name.display());
        result.put("level", snap.level());
        result.put("configuredLevel", snap.configuredLevel());
        result.put("baselineLevel", snap.baselineLevel());
        return result;
    }

    /**
     * Apply {@code level} to {@code name}. Order: mutate the registry first
     * (so that audit + broadcast describe a real change), then audit, then
     * broadcast. Audit and broadcast failures are swallowed by their adapters —
     * the local change has already taken effect.
     *
     * @throws IllegalArgumentException if {@code level} can't be parsed
     */
    public String set(LoggerName name, String level, String triggeredBy) {
        final String normalized = normalize(level);
        loggers.setLevel(name, normalized);
        audit.publishSet(name, normalized);
        broadcaster.broadcastSet(name, normalized, triggeredBy);
        return normalized;
    }

    /**
     * Restore {@code name} to its startup baseline, or clear the configured
     * level when no baseline was recorded (e.g. for a logger created post-startup
     * via the synthetic-row affordance).
     */
    public void reset(LoggerName name, String triggeredBy) {
        final String baseline = baselines.get(name);
        if (baseline == null) {
            loggers.clearLevel(name);
        } else {
            loggers.setLevel(name, baseline);
        }
        audit.publishReset(name, baseline);
        broadcaster.broadcastReset(name, triggeredBy);
    }

    private LoggerInfo snapshot(LoggerName name) {
        return new LoggerInfo(
                loggers.effectiveLevel(name),
                loggers.configuredLevel(name),
                baselines.get(name));
    }

    /**
     * Trim and uppercase the input, rejecting blanks. The actual "is this a
     * recognized level" decision is delegated to the registry — under the
     * JBoss LogManager that supports both JUL names ({@code WARNING}, {@code FINE},
     * …) and JBoss aliases ({@code WARN}, {@code DEBUG}, …) — and surfaces as an
     * {@link IllegalArgumentException} from {@link LoggerRegistry#setLevel}.
     */
    private static String normalize(String level) {
        if (level == null || level.isBlank()) {
            throw new IllegalArgumentException("level is required");
        }
        return level.trim().toUpperCase(Locale.ROOT);
    }
}
