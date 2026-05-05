package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LogLevelService} using simple in-memory test doubles for the
 * ports — no Mockito needed at the application layer. The fakes document the
 * contract each port is expected to honor at runtime.
 */
class LogLevelServiceTest {

    private static final LoggerName FOO = new LoggerName("foo");
    private static final LoggerName BAR = new LoggerName("bar");

    private FakeLoggerRegistry loggers;
    private FakeBaselineRegistry baselines;
    private RecordingClusterBroadcaster broadcaster;
    private RecordingAuditPublisher audit;
    private LogLevelService service;

    @BeforeEach
    void setUp() {
        loggers = new FakeLoggerRegistry();
        baselines = new FakeBaselineRegistry();
        broadcaster = new RecordingClusterBroadcaster();
        audit = new RecordingAuditPublisher();
        service = new LogLevelService(loggers, baselines, broadcaster, audit);
    }

    @Test
    void set_appliesLevelToRegistry() {
        service.set(FOO, "DEBUG", "alice");

        assertEquals("DEBUG", loggers.configuredLevel(FOO));
    }

    @Test
    void set_normalizesLowercaseInput() {
        service.set(FOO, "debug", "alice");

        assertEquals("DEBUG", loggers.configuredLevel(FOO));
    }

    @Test
    void set_trimsWhitespace() {
        service.set(FOO, "  DEBUG  ", "alice");

        assertEquals("DEBUG", loggers.configuredLevel(FOO));
    }

    @Test
    void set_rejectsBlankLevel() {
        assertThrows(IllegalArgumentException.class, () -> service.set(FOO, "   ", "alice"));
    }

    @Test
    void set_rejectsNullLevel() {
        assertThrows(IllegalArgumentException.class, () -> service.set(FOO, null, "alice"));
    }

    @Test
    void set_rejectsUnknownLevel() {
        assertThrows(IllegalArgumentException.class, () -> service.set(FOO, "BOGUS", "alice"));
    }

    @Test
    void set_publishesAudit() {
        service.set(FOO, "DEBUG", "alice");

        assertEquals(List.of(new SetCall(FOO, "DEBUG")), audit.sets);
    }

    @Test
    void set_broadcastsCluster() {
        service.set(FOO, "DEBUG", "alice");

        assertEquals(List.of(new SetBroadcast(FOO, "DEBUG", "alice")), broadcaster.sets);
    }

    @Test
    void set_doesNotAuditOrBroadcast_whenLevelInvalid() {
        // Validation precedes the registry mutation, so audit + broadcast must not fire.
        assertThrows(IllegalArgumentException.class, () -> service.set(FOO, "BOGUS", "alice"));

        assertTrue(audit.sets.isEmpty());
        assertTrue(broadcaster.sets.isEmpty());
    }

    @Test
    void reset_appliesBaseline_whenSnapshotted() {
        baselines.put(FOO, "WARNING");
        loggers.setLevel(FOO, "DEBUG");

        service.reset(FOO, "alice");

        assertEquals("WARNING", loggers.configuredLevel(FOO));
    }

    @Test
    void reset_clearsLevel_whenNoBaseline() {
        loggers.setLevel(FOO, "DEBUG");

        service.reset(FOO, "alice");

        assertNull(loggers.configuredLevel(FOO));
    }

    @Test
    void reset_publishesAudit_withBaselineWhenPresent() {
        baselines.put(FOO, "WARNING");

        service.reset(FOO, "alice");

        assertEquals(List.of(new ResetCall(FOO, "WARNING")), audit.resets);
    }

    @Test
    void reset_publishesAudit_withNullPostResetWhenNoBaseline() {
        service.reset(FOO, "alice");

        assertEquals(List.of(new ResetCall(FOO, null)), audit.resets);
    }

    @Test
    void reset_broadcastsCluster() {
        service.reset(FOO, "alice");

        assertEquals(List.of(new ResetBroadcast(FOO, "alice")), broadcaster.resets);
    }

    @Test
    void list_returnsAllRegisteredLoggers() {
        loggers.register(FOO, "DEBUG");
        loggers.register(BAR, null);

        final Map<String, LoggerInfo> result = service.list();

        assertEquals(2, result.size());
        assertEquals("DEBUG", result.get("foo").configuredLevel());
        assertNull(result.get("bar").configuredLevel());
    }

    @Test
    void list_isOrderedAlphabetically() {
        loggers.register(new LoggerName("zzz"), null);
        loggers.register(new LoggerName("aaa"), null);

        assertEquals(List.of("aaa", "zzz"), List.copyOf(service.list().keySet()));
    }

    @Test
    void list_displaysRootAsAlias() {
        loggers.register(LoggerName.ROOT, "INFO");

        assertTrue(service.list().containsKey("ROOT"));
    }

    @Test
    void get_returnsSnapshotForSingleLogger() {
        loggers.register(FOO, "DEBUG");
        baselines.put(FOO, "WARNING");

        final Map<String, String> result = service.get(FOO);

        assertEquals("foo", result.get("logger"));
        assertEquals("DEBUG", result.get("level"));
        assertEquals("DEBUG", result.get("configuredLevel"));
        assertEquals("WARNING", result.get("baselineLevel"));
    }

    @Test
    void get_displaysRootAsAlias() {
        loggers.register(LoggerName.ROOT, "INFO");

        assertEquals("ROOT", service.get(LoggerName.ROOT).get("logger"));
    }

    /** In-memory adapter that mimics the JBoss LogManager contract closely enough to test the service. */
    private static class FakeLoggerRegistry implements LoggerRegistry {

        private static final Set<String> KNOWN_LEVELS = Set.of(
                "OFF", "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST", "ALL",
                "ERROR", "WARN", "DEBUG", "TRACE");

        private final Map<LoggerName, String> configured = new HashMap<>();

        void register(LoggerName name, String level) {
            configured.put(name, level);
        }

        @Override
        public List<LoggerName> allNames() {
            return List.copyOf(configured.keySet());
        }

        @Override
        public String effectiveLevel(LoggerName name) {
            final String level = configured.get(name);
            return level != null ? level : "INFO";
        }

        @Override
        public String configuredLevel(LoggerName name) {
            return configured.get(name);
        }

        @Override
        public void setLevel(LoggerName name, String level) {
            if (!KNOWN_LEVELS.contains(level)) {
                throw new IllegalArgumentException("unknown level: " + level);
            }
            configured.put(name, level);
        }

        @Override
        public void clearLevel(LoggerName name) {
            configured.remove(name);
        }
    }

    private static class FakeBaselineRegistry implements BaselineRegistry {

        private final Map<LoggerName, String> baselines = new HashMap<>();

        void put(LoggerName name, String level) {
            baselines.put(name, level);
        }

        @Override
        public String get(LoggerName name) {
            return baselines.get(name);
        }
    }

    private record SetBroadcast(LoggerName name, String level, String triggeredBy) {
    }

    private record ResetBroadcast(LoggerName name, String triggeredBy) {
    }

    private static class RecordingClusterBroadcaster implements ClusterBroadcaster {
        final List<SetBroadcast> sets = new ArrayList<>();
        final List<ResetBroadcast> resets = new ArrayList<>();

        @Override
        public void broadcastSet(LoggerName name, String level, String triggeredBy) {
            sets.add(new SetBroadcast(name, level, triggeredBy));
        }

        @Override
        public void broadcastReset(LoggerName name, String triggeredBy) {
            resets.add(new ResetBroadcast(name, triggeredBy));
        }
    }

    private record SetCall(LoggerName name, String level) {
    }

    private record ResetCall(LoggerName name, String postResetLevel) {
    }

    private static class RecordingAuditPublisher implements AuditPublisher {
        final List<SetCall> sets = new ArrayList<>();
        final List<ResetCall> resets = new ArrayList<>();

        @Override
        public void publishSet(LoggerName name, String level) {
            sets.add(new SetCall(name, level));
        }

        @Override
        public void publishReset(LoggerName name, String postResetLevel) {
            resets.add(new ResetCall(name, postResetLevel));
        }
    }
}
