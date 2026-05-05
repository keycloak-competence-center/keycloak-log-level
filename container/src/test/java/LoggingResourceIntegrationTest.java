import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.testcontainers.junit.jupiter.Testcontainers;
import sut.SystemUnderTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for the keycloak-log-level extension running inside the
 * custom Keycloak container built by this module.
 */
@Tag("integration")
@Testcontainers
class LoggingResourceIntegrationTest {

    private static final String TARGET_LOGGER = "test.it.com.inventage.keycloak.loglevel";

    private static SystemUnderTest sut;
    private static Keycloak adminClient;
    private static HttpClient http;
    private static ObjectMapper json;
    private static String baseUrl;

    @BeforeAll
    static void beforeAll() {
        sut = SystemUnderTest.start();
        adminClient = KeycloakBuilder.builder()
                .serverUrl(sut.keycloak.getAuthServerUrl())
                .realm("master")
                .clientId("admin-cli")
                .username(sut.keycloak.getAdminUsername())
                .password(sut.keycloak.getAdminPassword())
                .build();
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        json = new ObjectMapper();
        baseUrl = sut.keycloak.getAuthServerUrl();
    }

    @AfterAll
    static void afterAll() {
        if (adminClient != null) {
            adminClient.close();
        }
        sut.stop();
    }

    @AfterEach
    void resetTouchedLogger() throws Exception {
        sendAuthed("DELETE", "/realms/master/logging/" + TARGET_LOGGER, null);
    }

    @Test
    void list_returnsKeycloakLoggers_withEffectiveAndConfigured() throws Exception {
        final HttpResponse<String> response = sendAuthed("GET", "/realms/master/logging", null);

        assertEquals(200, response.statusCode(), response.body());
        final Map<String, Map<String, String>> body = json.readValue(response.body(), new TypeReference<>() {
        });
        assertFalse(body.isEmpty());
        assertTrue(body.keySet().stream().anyMatch(name -> name.startsWith("org.keycloak")),
                "expected org.keycloak.* loggers, got: " + body.keySet());
        for (final Map<String, String> entry : body.values()) {
            assertNotNull(entry.get("level"), "every entry must report an effective level");
            // configuredLevel is allowed to be null (inherited)
        }
    }

    @Test
    void get_returnsLevelDescriptorForRoot() throws Exception {
        final HttpResponse<String> response = sendAuthed("GET", "/realms/master/logging/ROOT", null);

        assertEquals(200, response.statusCode(), response.body());
        final Map<String, String> body = json.readValue(response.body(), new TypeReference<>() {
        });
        assertEquals("ROOT", body.get("logger"));
        assertNotNull(body.get("level"));
    }

    @Test
    void set_thenGet_reflectsNewLevel() throws Exception {
        final HttpResponse<String> put = sendAuthed("PUT",
                "/realms/master/logging/" + TARGET_LOGGER, "{\"level\":\"FINE\"}");
        assertEquals(200, put.statusCode(), put.body());

        final HttpResponse<String> get = sendAuthed("GET",
                "/realms/master/logging/" + TARGET_LOGGER, null);
        assertEquals(200, get.statusCode(), get.body());
        final Map<String, String> body = json.readValue(get.body(), new TypeReference<>() {
        });
        assertEquals("FINE", body.get("level"));
        assertEquals("FINE", body.get("configuredLevel"));
    }

    @Test
    void set_acceptsJBossAlias() throws Exception {
        final HttpResponse<String> response = sendAuthed("PUT",
                "/realms/master/logging/" + TARGET_LOGGER, "{\"level\":\"TRACE\"}");

        assertEquals(200, response.statusCode(), response.body());
    }

    @Test
    void set_returns400_forUnknownLevel() throws Exception {
        final HttpResponse<String> response = sendAuthed("PUT",
                "/realms/master/logging/" + TARGET_LOGGER, "{\"level\":\"BOGUS\"}");

        assertEquals(400, response.statusCode(), response.body());
    }

    @Test
    void reset_clearsConfiguredLevel_forLoggerWithoutStartupBaseline() throws Exception {
        // TARGET_LOGGER is a synthetic name not configured at startup, so its baseline
        // is null and DELETE clears the configured level (the legacy behavior).
        sendAuthed("PUT", "/realms/master/logging/" + TARGET_LOGGER, "{\"level\":\"FINE\"}");

        final HttpResponse<String> delete = sendAuthed("DELETE",
                "/realms/master/logging/" + TARGET_LOGGER, null);
        assertEquals(204, delete.statusCode());

        final HttpResponse<String> get = sendAuthed("GET",
                "/realms/master/logging/" + TARGET_LOGGER, null);
        final Map<String, String> body = json.readValue(get.body(), new TypeReference<>() {
        });
        assertNull(body.get("configuredLevel"));
        assertNull(body.get("baselineLevel"));
    }

    @Test
    void reset_restoresStartupBaseline_forKcLogLevelCategory() throws Exception {
        // The SUT boots with KC_LOG_LEVEL=...,com.inventage.test.loglevel.baseline:warn,
        // so this category has a captured baseline of WARN. After overriding to TRACE,
        // DELETE must restore it to WARN — not clear it to inherit from root INFO.
        final String baselineLogger = "com.inventage.test.loglevel.baseline";

        try {
            // Sanity check: GET reports the startup baseline.
            final HttpResponse<String> initial = sendAuthed("GET",
                    "/realms/master/logging/" + baselineLogger, null);
            final Map<String, String> initialBody = json.readValue(initial.body(), new TypeReference<>() {
            });
            assertEquals("WARN", initialBody.get("baselineLevel"));
            assertEquals("WARN", initialBody.get("configuredLevel"));

            // Override the level via PUT.
            assertEquals(200, sendAuthed("PUT",
                    "/realms/master/logging/" + baselineLogger, "{\"level\":\"TRACE\"}").statusCode());
            final HttpResponse<String> overridden = sendAuthed("GET",
                    "/realms/master/logging/" + baselineLogger, null);
            final Map<String, String> overriddenBody = json.readValue(overridden.body(), new TypeReference<>() {
            });
            assertEquals("TRACE", overriddenBody.get("configuredLevel"));

            // DELETE → expect baseline (WARN), not null.
            assertEquals(204, sendAuthed("DELETE",
                    "/realms/master/logging/" + baselineLogger, null).statusCode());
            final HttpResponse<String> reset = sendAuthed("GET",
                    "/realms/master/logging/" + baselineLogger, null);
            final Map<String, String> resetBody = json.readValue(reset.body(), new TypeReference<>() {
            });
            assertEquals("WARN", resetBody.get("configuredLevel"),
                    "DELETE should have restored the startup-configured WARN baseline");
        } finally {
            // Restore baseline (in case the test failed mid-way) so other tests start clean.
            sendAuthed("DELETE", "/realms/master/logging/" + baselineLogger, null);
        }
    }

    @Test
    void list_returns401_withoutBearerToken() throws Exception {
        final HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + "/realms/master/logging")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(401, response.statusCode());
    }

    @Test
    void masterTokenAgainstNonMasterRealm_isRejected() throws Exception {
        // The provider is registered on every realm (RealmResourceProvider),
        // but a master-issued token must not grant access through example1's mount point.
        // BearerTokenAuthenticator rejects the cross-realm token (401) before the realm guard fires.
        // The realm-guard 403 path is exercised by the unit test.
        final HttpResponse<String> response = sendAuthed("GET", "/realms/example1/logging", null);

        assertTrue(response.statusCode() == 401 || response.statusCode() == 403,
                "expected 401 or 403 from non-master realm, got " + response.statusCode()
                        + " body=" + response.body());
    }

    @Test
    void masterRealm_hasLogLevelAdminThemeConfigured() {
        // Sanity check: keycloak-config-cli should have applied adminTheme=keycloak-log-level
        // from realm-master.json. If this fails, the theme jar may not have been loaded
        // (theme not in available list) or the config import skipped the property.
        final org.keycloak.representations.idm.RealmRepresentation master =
                adminClient.realm("master").toRepresentation();
        assertEquals("keycloak-log-level", master.getAdminTheme(),
                "master realm adminTheme should be keycloak-log-level; available admin themes need to include it");
    }

    @Test
    void levelChange_actuallySuppressesAndRestoresLogOutput() throws Exception {
        // Use the LoggingResourceProvider's own logger as a self-canary: when its
        // category is set to OFF, the *next* INFO line emitted by the provider is
        // suppressed — and the very PUT that does the silencing is itself such a
        // line, since the level change happens before LOG.infof in set().
        // When we later reset the category, the corresponding INFO line for the
        // reset call is emitted, proving the effective level changed in both directions.
        final String selfLogger = "com.inventage.keycloak.loglevel.infrastructure.LoggingResourceProvider";
        final String secondLogger = "com.inventage.test.loglevel.example";
        final String setOffLine = "Log level for '" + selfLogger + "' set to OFF";
        final String setSecondLine = "Log level for '" + secondLogger + "' set to DEBUG";
        final String resetSelfLine = "Log level for '" + selfLogger + "' reset by";

        final int baseSetOff = countOccurrences(sut.keycloak.getLogs(), setOffLine);
        final int baseSetSecond = countOccurrences(sut.keycloak.getLogs(), setSecondLine);
        final int baseResetSelf = countOccurrences(sut.keycloak.getLogs(), resetSelfLine);

        try {
            // 1) Silence the provider's own category. The PUT itself is the canary.
            assertEquals(200,
                    sendAuthed("PUT", "/realms/master/logging/" + selfLogger, "{\"level\":\"OFF\"}").statusCode());
            // 2) Make a second change that should also be silenced.
            assertEquals(200,
                    sendAuthed("PUT", "/realms/master/logging/" + secondLogger, "{\"level\":\"DEBUG\"}").statusCode());

            waitForLogFlush();
            final String silencedSnapshot = sut.keycloak.getLogs();
            assertEquals(baseSetOff, countOccurrences(silencedSnapshot, setOffLine),
                    "expected the LR provider's own 'set to OFF' INFO line to be suppressed by the level change it just performed");
            assertEquals(baseSetSecond, countOccurrences(silencedSnapshot, setSecondLine),
                    "expected the second 'set to DEBUG' INFO line to also be suppressed while the LR category is OFF");

            // 3) Lift the silence. The DELETE's own INFO line should now appear.
            assertEquals(204, sendAuthed("DELETE", "/realms/master/logging/" + selfLogger, null).statusCode());

            waitForLogFlush();
            final String restoredSnapshot = sut.keycloak.getLogs();
            assertTrue(countOccurrences(restoredSnapshot, resetSelfLine) > baseResetSelf,
                    "expected the LR provider's 'reset by' INFO line to appear once silencing is lifted; baseline="
                            + baseResetSelf + ", current=" + countOccurrences(restoredSnapshot, resetSelfLine));
        } finally {
            sendAuthed("DELETE", "/realms/master/logging/" + selfLogger, null);
            sendAuthed("DELETE", "/realms/master/logging/" + secondLogger, null);
        }
    }

    private static void waitForLogFlush() throws InterruptedException {
        // Quarkus logs synchronously to stdout; testcontainers' getLogs() reads through
        // Docker's log API which has a small buffer. A short sleep is enough to be sure
        // the lines (or non-lines) we care about have been observed.
        Thread.sleep(750);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    private HttpResponse<String> sendAuthed(String method, String path, String body) throws Exception {
        final HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        final HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + adminClient.tokenManager().getAccessTokenString())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .method(method, publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
