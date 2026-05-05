package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.BaselineRegistry;
import com.inventage.keycloak.loglevel.application.LoggerRegistry;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import com.inventage.keycloak.loglevel.infrastructure.StartupBaselineRegistry;
import com.inventage.keycloak.loglevel.infrastructure.JulLoggerRegistry;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.managers.AppAuthManager.BearerTokenAuthenticator;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.mockito.MockedConstruction;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/**
 * REST-layer tests: auth gate, request shape, error mapping.
 *
 * <p>Business behavior (level normalization, broadcast, audit, baseline reset
 * semantics) is covered in {@code LogLevelServiceTest} with simple test doubles
 * — those tests don't need any Keycloak mocks. This class only verifies what the
 * REST resource adds on top: 401/403 responses, 400 error mapping for invalid
 * input, and that successful calls pass through.
 */
class LoggingResourceProviderTest {

    private static final String LOGGER_NAME = "test.unit.com.inventage.keycloak.loglevel";

    private KeycloakSession session;
    private RealmModel realm;
    private RoleModel adminRole;
    private UserModel user;
    private AuthResult authResult;
    private LoggingResourceProvider provider;
    private MockedConstruction<BearerTokenAuthenticator> bearerCtor;
    private LoggerRegistry loggers;
    private StartupBaselineRegistry baselines;

    @BeforeEach
    void setUp() {
        session = mock(KeycloakSession.class);
        final KeycloakContext ctx = mock(KeycloakContext.class);
        realm = mock(RealmModel.class);
        adminRole = mock(RoleModel.class);
        user = mock(UserModel.class);
        authResult = mock(AuthResult.class);

        when(session.getContext()).thenReturn(ctx);
        when(ctx.getRealm()).thenReturn(realm);
        when(realm.getName()).thenReturn("master");
        when(realm.getRole("admin")).thenReturn(adminRole);
        when(authResult.user()).thenReturn(user);
        when(user.hasRole(adminRole)).thenReturn(true);
        when(user.getUsername()).thenReturn("test-admin");

        when(session.getProvider(ClusterProvider.class)).thenReturn(mock(ClusterProvider.class));

        bearerCtor = mockConstruction(BearerTokenAuthenticator.class,
                (m, c) -> when(m.authenticate()).thenReturn(authResult));

        loggers = new JulLoggerRegistry();
        baselines = new StartupBaselineRegistry();
        provider = new LoggingResourceProvider(session, loggers, baselines);
    }

    @AfterEach
    void tearDown() {
        bearerCtor.close();
        Logger.getLogger(LOGGER_NAME).setLevel(null);
        baselines.clearForTesting();
    }

    @Test
    void set_returns200_andApplies_onValidLevel() {
        final Response response = provider.set(LOGGER_NAME, new LoggingResourceProvider.LevelRequest("FINE"));

        assertEquals(200, response.getStatus());
        assertEquals(Level.FINE, Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void set_returns400_whenBodyIsNull() {
        final Response response = provider.set(LOGGER_NAME, null);

        assertEquals(400, response.getStatus());
        assertNull(Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void set_returns400_whenLevelIsBlank() {
        final Response response = provider.set(LOGGER_NAME, new LoggingResourceProvider.LevelRequest("   "));

        assertEquals(400, response.getStatus());
        assertNull(Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void set_returns400_whenLevelIsUnknown() {
        final Response response = provider.set(LOGGER_NAME, new LoggingResourceProvider.LevelRequest("BOGUS"));

        assertEquals(400, response.getStatus());
        assertNull(Logger.getLogger(LOGGER_NAME).getLevel());
    }

    @Test
    void reset_returns204() {
        Logger.getLogger(LOGGER_NAME).setLevel(Level.FINE);

        final Response response = provider.reset(LOGGER_NAME);

        assertEquals(204, response.getStatus());
    }

    @Test
    void get_returnsLogger() {
        Logger.getLogger(LOGGER_NAME).setLevel(Level.WARNING);

        assertEquals("WARNING", provider.get(LOGGER_NAME).get("level"));
    }

    @Test
    void list_throws401_whenNoBearerToken() {
        replaceBearerCtorWith(null);

        assertThrows(NotAuthorizedException.class, () -> provider.list());
    }

    @Test
    void list_throws403_whenNotMasterRealm() {
        when(realm.getName()).thenReturn("example1");

        assertThrows(ForbiddenException.class, () -> provider.list());
    }

    @Test
    void list_throws403_whenAdminRoleMissing() {
        when(realm.getRole("admin")).thenReturn(null);

        assertThrows(ForbiddenException.class, () -> provider.list());
    }

    @Test
    void list_throws403_whenUserLacksAdminRole() {
        when(user.hasRole(adminRole)).thenReturn(false);

        assertThrows(ForbiddenException.class, () -> provider.list());
    }

    @Test
    void rootAlias_isAccepted() {
        // Smoke test that the LoggerName.parse path works through the REST layer.
        assertEquals("ROOT", provider.get(LoggerName.ROOT_ALIAS).get("logger"));
    }

    private void replaceBearerCtorWith(AuthResult result) {
        bearerCtor.close();
        bearerCtor = mockConstruction(BearerTokenAuthenticator.class,
                (m, c) -> when(m.authenticate()).thenReturn(result));
        provider = new LoggingResourceProvider(session, loggers, baselines);
    }
}
