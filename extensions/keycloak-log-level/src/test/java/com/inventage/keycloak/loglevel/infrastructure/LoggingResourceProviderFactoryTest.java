package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.infrastructure.KeycloakClusterBroadcaster;
import com.inventage.keycloak.loglevel.infrastructure.LogLevelChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterListener;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.mockito.ArgumentCaptor;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies the cluster-listener side of the factory: when another node
 * broadcasts a {@link LogLevelChangeEvent}, the listener registered in
 * {@link LoggingResourceProviderFactory#postInit} applies the level locally
 * via the wired {@code LoggerRegistry} adapter.
 */
class LoggingResourceProviderFactoryTest {

    private static final String LOGGER_NAME = "test.factory.com.inventage.keycloak.loglevel";
    /**
     * Strong reference to the test logger held for the lifetime of the JVM.
     * JBoss LogManager prunes loggers whose level is cleared and that have no
     * external strong references, which causes intermittent test failures
     * when {@code @AfterEach} clears the level and the next test's snapshot
     * runs before its own {@code setLevel} has anchored a fresh Logger. The
     * static field eliminates that race.
     */
    @SuppressWarnings("unused")
    private static final Logger PIN = Logger.getLogger(LOGGER_NAME);

    private LoggingResourceProviderFactory factory;

    @BeforeEach
    void setUp() {
        Logger.getLogger(LOGGER_NAME).setLevel(null);
        factory = new LoggingResourceProviderFactory();
    }

    @AfterEach
    void cleanUp() {
        Logger.getLogger(LOGGER_NAME).setLevel(null);
        factory.baselineRegistry().clearForTesting();
    }

    @Test
    void clusterListener_appliesSetEvent_locally() {
        final ClusterListener listener = registerAndCaptureListener();

        listener.eventReceived(LogLevelChangeEvent.set(LOGGER_NAME, "FINE", "remote-admin"));

        assertEquals(Level.FINE, Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void clusterListener_resetEvent_restoresBaseline() {
        // Logger configured at WARNING when postInit captures the baseline.
        Logger.getLogger(LOGGER_NAME).setLevel(Level.WARNING);
        final ClusterListener listener = registerAndCaptureListener();

        // Local override (could have been a UI change on this node).
        Logger.getLogger(LOGGER_NAME).setLevel(Level.FINE);

        // Receiving a reset event from another node restores the captured baseline.
        listener.eventReceived(LogLevelChangeEvent.reset(LOGGER_NAME, "remote-admin"));

        assertEquals(Level.WARNING, Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void clusterListener_resetEvent_clearsLevel_whenNoBaseline() {
        // Logger inheriting at postInit time → no baseline.
        final ClusterListener listener = registerAndCaptureListener();

        Logger.getLogger(LOGGER_NAME).setLevel(Level.FINE);
        listener.eventReceived(LogLevelChangeEvent.reset(LOGGER_NAME, "remote-admin"));

        assertNull(Logger.getLogger(LOGGER_NAME).getLevel());
    }

    private ClusterListener registerAndCaptureListener() {
        final KeycloakSessionFactory sessionFactory = mock(KeycloakSessionFactory.class);
        final KeycloakSession session = mock(KeycloakSession.class);
        final ClusterProvider cluster = mock(ClusterProvider.class);
        when(sessionFactory.create()).thenReturn(session);
        when(session.getProvider(ClusterProvider.class)).thenReturn(cluster);

        factory.postInit(sessionFactory);

        final ArgumentCaptor<ClusterListener> captor = ArgumentCaptor.forClass(ClusterListener.class);
        verify(cluster).registerListener(eq(KeycloakClusterBroadcaster.CLUSTER_TASK_KEY), captor.capture());
        return captor.getValue();
    }
}
