package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.LoggerRegistry;
import com.inventage.keycloak.loglevel.domain.LoggerName;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Adapter implementing {@link LoggerRegistry} on top of {@link LogManager} /
 * {@link Logger}. Under Keycloak/Quarkus the active LogManager is the JBoss
 * LogManager, so this adapter also accepts the JBoss aliases ({@code DEBUG},
 * {@code TRACE}, {@code WARN}, {@code ERROR}) — {@link Level#parse(String)}
 * resolves them when the JBoss LogManager is in place.
 *
 * <p>Stateless and JVM-global; safe to share as a singleton.
 */
public class JulLoggerRegistry implements LoggerRegistry {

    private static final String DEFAULT_EFFECTIVE = Level.INFO.getName();

    /**
     * Names of every logger ever observed in {@link LogManager#getLoggerNames}.
     *
     * <p>JBoss LogManager keeps {@link Logger} instances by weak reference and
     * prunes ones with no level configured + no external strong refs. That
     * pruning can briefly drop class-named leaf loggers between requests, which
     * makes the UI's path-segment classification flicker — a category like
     * {@code com.inventage} is correctly hidden as a path-segment when its
     * leaf-class descendants are visible, then "reappears" as a state-A inherited
     * row once the leaves get GC'd, then vanishes entirely once the parent gets
     * pruned too. Caching the names monotonically here makes the response
     * stable: once a name appears, it stays in the response for the lifetime
     * of the JVM, and the UI's classification doesn't depend on pruning timing.
     *
     * <p>Memory cost is bounded by total distinct logger names ever seen — a
     * few hundred for a typical Keycloak deployment.
     */
    private static final Set<String> EVER_SEEN = ConcurrentHashMap.newKeySet();

    @Override
    public List<LoggerName> allNames() {
        final LogManager mgr = LogManager.getLogManager();
        mgr.getLoggerNames().asIterator().forEachRemaining(name -> {
            if (mgr.getLogger(name) != null) {
                EVER_SEEN.add(name);
            }
        });
        // Some LogManager implementations (notably the JBoss LogManager) do not
        // include the unnamed root logger in getLoggerNames(). Add it explicitly
        // so the UI can always anchor inherited loggers under a ROOT entry.
        if (mgr.getLogger("") != null) {
            EVER_SEEN.add("");
        }

        final List<LoggerName> names = new ArrayList<>(EVER_SEEN.size());
        for (final String name : EVER_SEEN) {
            names.add(name.isEmpty() ? LoggerName.ROOT : new LoggerName(name));
        }
        return names;
    }

    @Override
    public String effectiveLevel(LoggerName name) {
        final LogManager mgr = LogManager.getLogManager();
        Logger probe = mgr.getLogger(name.value());
        if (probe != null) {
            Level level = probe.getLevel();
            while (level == null && probe.getParent() != null) {
                probe = probe.getParent();
                level = probe.getLevel();
            }
            if (level != null) {
                return level.getName();
            }
        }
        // Logger has been pruned (or never existed) — walk the dotted-name chain
        // looking up each ancestor by name, falling back to ROOT, then INFO.
        String ancestor = name.value();
        while (ancestor.contains(".")) {
            ancestor = ancestor.substring(0, ancestor.lastIndexOf('.'));
            final Logger anc = mgr.getLogger(ancestor);
            if (anc != null && anc.getLevel() != null) {
                return anc.getLevel().getName();
            }
        }
        final Logger root = mgr.getLogger("");
        if (root != null && root.getLevel() != null) {
            return root.getLevel().getName();
        }
        return DEFAULT_EFFECTIVE;
    }

    @Override
    public String configuredLevel(LoggerName name) {
        final Logger logger = LogManager.getLogManager().getLogger(name.value());
        if (logger == null) {
            return null;
        }
        final Level level = logger.getLevel();
        return level == null ? null : level.getName();
    }

    @Override
    public void setLevel(LoggerName name, String level) {
        // Logger.getLogger lazily creates the category if it doesn't exist —
        // intentional, so the synthetic-row affordance can promote a category
        // the operator types into search.
        Logger.getLogger(name.value()).setLevel(Level.parse(level));
    }

    @Override
    public void clearLevel(LoggerName name) {
        // Use LogManager.getLogger (read-only lookup) instead of Logger.getLogger
        // so we don't lazily create a category just to clear it.
        final Logger logger = LogManager.getLogManager().getLogger(name.value());
        if (logger != null) {
            logger.setLevel(null);
        }
    }
}
