import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.TimeoutError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import sut.SystemUnderTest;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end browser test for the admin console integration: drives a real
 * Chromium against the running Keycloak container, logs in as the master admin,
 * verifies the injected "Log levels" sidebar entry navigates to the standalone
 * page, and exercises the set/reset flow against a live logger.
 */
@Tag("integration")
@Testcontainers
class AdminConsoleUiPlaywrightTest {

    private static final long NAV_TIMEOUT_MS = 60_000;
    private static final long ACTION_TIMEOUT_MS = 15_000;
    /**
     * Logger fixture used by both the canary tests and the "inherited" tests.
     * {@link com.inventage.keycloak.loglevel.infrastructure.LoggingResourceProvider}
     * holds its own category via a {@code private static final Logger LOG} field,
     * so this name is guaranteed to be present in {@code LogManager.getLoggerNames()}
     * for the lifetime of the JVM. (JBoss LogManager prunes named categories that
     * have null level and no external strong references — using an arbitrary
     * synthetic name as the inherited fixture is unreliable for that reason.)
     * In its default state it has no configured level, i.e. it appears as
     * inherited from ROOT.
     */
    private static final String SELF_LOGGER =
            "com.inventage.keycloak.loglevel.infrastructure.LoggingResourceProvider";
    /**
     * A package-level category that no Logger instance exists for at startup.
     * UUID-suffixed so each container run targets a fresh name — the test
     * relies on the category not being in {@code getLoggerNames()} when it
     * starts. The name is intentionally NOT added to {@link #LOGGERS_TO_RESET}:
     * the per-test cleanup uses {@code DELETE} which would force-create the
     * logger via {@code Logger.getLogger()} in the reset handler, defeating
     * the test's whole premise. The container is ephemeral so leakage between
     * tests doesn't matter.
     */
    private static final String PROSPECTIVE_LOGGER =
            "com.inventage.test.loglevel.prospective."
                    + java.util.UUID.randomUUID().toString().replace("-", "");
    private static final java.util.List<String> LOGGERS_TO_RESET =
            java.util.List.of(SELF_LOGGER);
    private static final Pattern ACCESS_TOKEN = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

    private static SystemUnderTest sut;
    private static Playwright playwright;
    private static Browser browser;
    private static HttpClient adminHttp;

    @BeforeAll
    static void beforeAll() {
        sut = SystemUnderTest.start();
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        adminHttp = HttpClient.newHttpClient();
    }

    /**
     * Returns the SUT URL with the host rewritten to {@code localhost}.
     *
     * <p>Browsers gate {@code window.crypto.subtle} (used by our PKCE S256 flow) behind a
     * secure context. {@code localhost} is treated as secure regardless of HTTPS, while the
     * docker-bridge IP testcontainers normally returns is not. The published port is still
     * the same — Docker binds to 0.0.0.0, so localhost reaches the same container.
     */
    private static String localhostBase() {
        return "http://localhost:" + URI.create(sut.keycloak.getAuthServerUrl()).getPort();
    }

    @BeforeEach
    void resetTouchedLoggersBeforeTest() {
        resetTouchedLoggers();
    }

    @AfterEach
    void resetTouchedLoggersAfterTest() {
        resetTouchedLoggers();
    }

    /**
     * Tests change a few logger categories (LR's own logger as a canary, plus
     * the org.keycloak.services target). Reset them around every test so each
     * starts from a known inherited state and a partial failure can't leak
     * configured levels into subsequent tests.
     */
    private static void resetTouchedLoggers() {
        try {
            final String token = acquireAdminToken();
            if (token == null) {
                return;
            }
            for (final String logger : LOGGERS_TO_RESET) {
                adminHttp.send(
                        HttpRequest.newBuilder(URI.create(
                                        localhostBase() + "/realms/master/logging/" + logger))
                                .header("Authorization", "Bearer " + token)
                                .DELETE()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
            }
        }
        catch (Exception ignored) {
            // best-effort
        }
    }

    @AfterAll
    static void afterAll() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (sut != null) {
            sut.stop();
        }
    }

    @Test
    void login_recoversWhenAuthenticationFlowIsRestarted() {
        // Deterministic regression test for the first-login cookie race (see
        // newSignedInContext javadoc): losing the race leaves the browser with an
        // AUTH_SESSION_ID that doesn't match the login form's authentication
        // session. Simulate exactly that by corrupting the cookie before the
        // first submit; Keycloak restarts the flow and re-renders the login form,
        // and the sign-in helper must recover by re-submitting.
        try (BrowserContext ctx = newSignedInContext(
                AdminConsoleUiPlaywrightTest::corruptAuthSessionCookie)) {
            assertTrue(ctx.pages().get(0).locator("#kc-log-level-nav-item").isVisible(),
                    "sign-in helper should reach the admin console despite the restarted login flow");
        }
    }

    /**
     * Replace the value of every AUTH_SESSION_ID* cookie with a stale one, keeping
     * all other attributes (domain, path, expiry, …) so the browser still sends it.
     * This mimics the pre-auth probe's Set-Cookie landing after the login page's.
     */
    private static void corruptAuthSessionCookie(Page page) {
        final BrowserContext ctx = page.context();
        final java.util.List<com.microsoft.playwright.options.Cookie> stale =
                ctx.cookies().stream()
                        .filter(cookie -> cookie.name.startsWith("AUTH_SESSION_ID"))
                        .peek(cookie -> cookie.value = "stale-root-auth-session")
                        .toList();
        assertTrue(!stale.isEmpty(), "expected an AUTH_SESSION_ID cookie on the login page");
        ctx.addCookies(stale);
    }

    @Test
    void clickingMenuItem_navigatesToLogLevelsPage() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = ctx.pages().get(0);

            page.locator("#kc-log-level-nav-item a").click();
            page.waitForURL(url -> url.contains("/log-levels.html"),
                    new Page.WaitForURLOptions().setTimeout(NAV_TIMEOUT_MS));

            page.locator("#rows tr").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(NAV_TIMEOUT_MS));

            assertTrue(page.locator("#rows tr").count() > 0,
                    "log-levels table should be populated after silent SSO");
        }
    }

    @Test
    void uiLevelChange_actuallySuppressesAndRestoresLogOutput() {
        // Mirror of the HTTP IT's effective-output check, driven through the page UI.
        // Using LoggingResourceProvider's own category as a self-canary: setting it
        // to OFF suppresses the very INFO line that would otherwise be emitted for
        // the change. Resetting it lets the reset's own INFO line through. Both are
        // observable in the container's stdout via testcontainers.getLogs().
        final String setOffLine = "Log level for '" + SELF_LOGGER + "' set to OFF";
        final String resetSelfLine = "Log level for '" + SELF_LOGGER + "' reset by";

        final int baseSetOff = countOccurrences(sut.keycloak.getLogs(), setOffLine);
        final int baseResetSelf = countOccurrences(sut.keycloak.getLogs(), resetSelfLine);

        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = ctx.pages().get(0);
            page.locator("#kc-log-level-nav-item a").click();
            page.waitForURL(url -> url.contains("/log-levels.html"),
                    new Page.WaitForURLOptions().setTimeout(NAV_TIMEOUT_MS));
            page.locator("#rows tr").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(NAV_TIMEOUT_MS));

            page.locator("#filter").fill(SELF_LOGGER);
            page.locator("#rows tr").first().waitFor(
                    new Locator.WaitForOptions().setTimeout(ACTION_TIMEOUT_MS));

            // 1) UI sets the LR category to OFF. The change takes effect server-side
            //    before the corresponding INFO line would be emitted, so that line
            //    is suppressed even though the API call still returns 200.
            page.locator("#rows tr").first()
                    .locator("select.kc-ll-row__select")
                    .selectOption("OFF");
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr'))" +
                            ".find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)" +
                            "?.querySelector('.kc-ll-row__level')?.textContent === 'OFF'",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            sleepQuietly(750);
            assertEquals(baseSetOff, countOccurrences(sut.keycloak.getLogs(), setOffLine),
                    "UI 'set to OFF' should not have produced a server log line, since the "
                            + "category was just silenced");

            // 2) Reset via the UI. The DELETE's own INFO line should now appear,
            //    since the category falls back to inherited INFO.
            page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first()
                    .locator(".kc-ll-row__reset")
                    .click();
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr'))" +
                            ".find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)" +
                            "?.querySelector('.kc-ll-row__level')?.textContent !== 'OFF'",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            sleepQuietly(750);
            assertTrue(countOccurrences(sut.keycloak.getLogs(), resetSelfLine) > baseResetSelf,
                    "expected reset INFO line to appear after silencing was lifted");
        }
    }

    private static String acquireAdminToken() throws Exception {
        final String form = "grant_type=password&client_id=admin-cli"
                + "&username=" + URLEncoder.encode(sut.keycloak.getAdminUsername(), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(sut.keycloak.getAdminPassword(), StandardCharsets.UTF_8);
        final HttpResponse<String> response = adminHttp.send(
                HttpRequest.newBuilder(URI.create(
                                localhostBase() + "/realms/master/protocol/openid-connect/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "admin token grab failed: HTTP " + response.statusCode() + " — " + response.body());
        }
        final Matcher matcher = ACCESS_TOKEN.matcher(response.body());
        if (!matcher.find()) {
            throw new IllegalStateException("admin token grab returned 200 but no access_token in body: " + response.body());
        }
        return matcher.group(1);
    }

    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void defaultView_showsTreeOfConfiguredLoggersOnly() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            // ROOT is always configured (master KC_LOG_LEVEL=info) and is shown.
            assertEquals(1L, rowsWithName(page, "ROOT"),
                    "ROOT should always be a top-level row in the default tree");

            // SELF_LOGGER has no configured level by default, so it lives as an
            // inherited descendant under ROOT and is hidden until ROOT is expanded.
            assertEquals(0L, rowsWithName(page, SELF_LOGGER),
                    "an inherited logger should not appear in the default tree");

            // The same logger IS findable via the search input (flat mode).
            page.locator("#filter").fill(SELF_LOGGER);
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".some(e => e.textContent === name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            final Locator row = page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first();
            assertTrue((Boolean) row.evaluate("el => el.classList.contains('is-inherited')"),
                    "search-mode result for an inherited logger should carry the 'is-inherited' class");
        }
    }

    @Test
    void settingLevelOnInheritedLogger_promotesItToTopLevel() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            // Surface the inherited target via search.
            page.locator("#filter").fill(SELF_LOGGER);
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".some(e => e.textContent === name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Set DEBUG → row drops the inherited marker.
            page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first()
                    .locator("select.kc-ll-row__select")
                    .selectOption("DEBUG");
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr'))" +
                            ".find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)" +
                            "?.classList.contains('is-inherited') === false",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Clear the filter — SELF_LOGGER should now be a top-level row in the
            // tree (where before the change it didn't appear at all).
            page.locator("#filter").fill("");
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".some(e => e.textContent === name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
            assertEquals(1L, rowsWithName(page, SELF_LOGGER));

            // Reset → row drops back out of the default tree.
            page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first()
                    .locator(".kc-ll-row__reset")
                    .click();
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".every(e => e.textContent !== name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    @Test
    void expandingRootRow_revealsInheritedDescendants() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            // ROOT sorts first, so #rows tr:first-child is the ROOT row.
            final Locator rootRow = page.locator("#rows tr").first();
            assertEquals("ROOT", rootRow.locator(".kc-ll-row__name").textContent());

            final Locator expandBtn = rootRow.locator(".kc-ll-row__expand");
            expandBtn.waitFor(new Locator.WaitForOptions().setTimeout(ACTION_TIMEOUT_MS));

            expandBtn.click();
            page.waitForFunction(
                    "() => document.querySelectorAll('#rows tr[data-parent=\"ROOT\"]').length > 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            expandBtn.click();
            page.waitForFunction(
                    "() => document.querySelectorAll('#rows tr[data-parent=\"ROOT\"]').length === 0",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    @Test
    void overridingLogger_marksRowAsOverridden_andShowsReset() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            // Surface the inherited target via search.
            page.locator("#filter").fill(SELF_LOGGER);
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".some(e => e.textContent === name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Inherited row, no baseline → not overridden, Reset hidden.
            Locator row = page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first();
            assertEquals(false, row.evaluate("el => el.classList.contains('is-overridden')"),
                    "an inherited logger with no baseline should not be marked overridden");
            assertEquals("hidden",
                    row.locator(".kc-ll-row__reset").evaluate("el => getComputedStyle(el).visibility"),
                    "Reset must stay hidden on a non-overridden row");

            // Set DEBUG: row gains is-overridden, Reset becomes visible.
            row.locator("select.kc-ll-row__select").selectOption("DEBUG");
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr'))" +
                            ".find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)" +
                            "?.classList.contains('is-overridden') === true",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
            row = page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first();
            assertEquals("visible",
                    row.locator(".kc-ll-row__reset").evaluate("el => getComputedStyle(el).visibility"),
                    "Reset must be revealed on an overridden row");

            // Reset: row drops is-overridden (still searchable, just back to inherited).
            row.locator(".kc-ll-row__reset").click();
            page.waitForFunction(
                    "name => {" +
                            "  const tr = Array.from(document.querySelectorAll('#rows tr'))" +
                            "    .find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name);" +
                            "  return !tr || !tr.classList.contains('is-overridden');" +
                            "}",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    @Test
    void searchingForUnregisteredCategory_offersProspectiveRow_andSettingLevelCreatesIt() {
        // Targets a name that no Logger exists for at startup. Before the change
        // the search would yield zero rows; the synthetic row affordance lets
        // the operator pick a level on it directly.
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            page.locator("#filter").fill(PROSPECTIVE_LOGGER);

            // A single prospective row appears at the top of an otherwise empty
            // result set. The visible level is what the category would inherit
            // from its nearest configured ancestor (ROOT = INFO in this SUT).
            page.waitForFunction(
                    "name => {" +
                            "  const rows = document.querySelectorAll('#rows tr');" +
                            "  return rows.length === 1 && " +
                            "    rows[0].classList.contains('is-prospective') && " +
                            "    rows[0].querySelector('.kc-ll-row__name')?.textContent === name;" +
                            "}",
                    PROSPECTIVE_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Set DEBUG via the dropdown; the server creates the category lazily,
            // and on reload the row should now be a real configured logger
            // (no longer prospective, carries is-overridden).
            page.locator("#rows tr").first()
                    .locator("select.kc-ll-row__select")
                    .selectOption("DEBUG");
            page.waitForFunction(
                    "name => {" +
                            "  const tr = Array.from(document.querySelectorAll('#rows tr'))" +
                            "    .find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name);" +
                            "  return tr && !tr.classList.contains('is-prospective')" +
                            "    && tr.classList.contains('is-overridden')" +
                            "    && tr.querySelector('.kc-ll-row__level')?.textContent === 'DEBUG';" +
                            "}",
                    PROSPECTIVE_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    @Test
    void searchingForAncestorPackage_showsProspectiveAlongsideChildMatches() throws Exception {
        // The user case: typing a package prefix (e.g. `com.inventage`) that
        // isn't itself a registered Logger but is a substring of leaf categories.
        // The substring filter matches the leaves, AND the prospective row
        // appears at the top so the operator can set a level on the package.
        //
        // We pre-create a fresh child logger via the REST API so the substring
        // filter is guaranteed to have a non-prospective match. Relying on
        // class-static loggers like LoggingResourceProvider isn't reliable here:
        // JBoss LogManager prunes JUL Loggers whose level is cleared and whose
        // strong JUL references are gone, and SELF_LOGGER is in exactly that
        // state across @BeforeEach + page navigation timing.
        final String pkg = "com.inventage";
        final String childLogger = pkg + ".ancestortest."
                + java.util.UUID.randomUUID().toString().replace("-", "");
        putLevelViaApi(childLogger, "DEBUG");
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            page.locator("#filter").fill(pkg);

            page.waitForFunction(
                    "name => {" +
                            "  const rows = Array.from(document.querySelectorAll('#rows tr'));" +
                            "  if (rows.length < 2) return false;" +
                            "  const first = rows[0];" +
                            "  const firstName = first.querySelector('.kc-ll-row__name')?.textContent;" +
                            "  return first.classList.contains('is-prospective')" +
                            "    && firstName === name" +
                            "    && rows.slice(1).some(tr => !tr.classList.contains('is-prospective'));" +
                            "}",
                    pkg,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    private static void putLevelViaApi(String logger, String level) throws Exception {
        final String token = acquireAdminToken();
        final HttpResponse<String> response = adminHttp.send(
                HttpRequest.newBuilder(URI.create(
                                localhostBase() + "/realms/master/logging/" + logger))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"level\":\"" + level + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "putLevelViaApi(" + logger + ", " + level + ") failed: HTTP "
                            + response.statusCode() + " — " + response.body());
        }
    }

    @Test
    void resettingSyntheticPromotedLogger_replacesRowWithSynthetic() throws Exception {
        // Mirrors the user-reported flow: type a package name, set a level,
        // reset. After the reset the row should NOT linger as a state-A entry
        // — the synthetic should re-appear at the top, mirroring the state
        // before any change was made. The path-segment intermediates JBoss
        // LogManager materializes when the level is set should also not show
        // up in the search results.
        final String pkg = "com.inventage.path." + java.util.UUID.randomUUID().toString().replace("-", "");
        // Pre-populate a leaf descendant via API so JBoss LogManager has a
        // reason to materialize intermediate path segments once `pkg` is set.
        final String leaf = pkg + ".leaf";
        putLevelViaApi(leaf, "DEBUG");
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            page.locator("#filter").fill(pkg);

            // Initially `pkg` doesn't exist as a Logger → synthetic appears.
            page.waitForFunction(
                    "name => {" +
                            "  const rows = Array.from(document.querySelectorAll('#rows tr'));" +
                            "  const first = rows[0];" +
                            "  return first && first.classList.contains('is-prospective')" +
                            "    && first.querySelector('.kc-ll-row__name')?.textContent === name;" +
                            "}",
                    pkg,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Set DEBUG via the synthetic dropdown → real Logger created.
            page.locator("#rows tr").first()
                    .locator("select.kc-ll-row__select")
                    .selectOption("DEBUG");
            page.waitForFunction(
                    "name => {" +
                            "  const tr = Array.from(document.querySelectorAll('#rows tr'))" +
                            "    .find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name);" +
                            "  return tr && !tr.classList.contains('is-prospective')" +
                            "    && tr.querySelector('.kc-ll-row__level')?.textContent === 'DEBUG';" +
                            "}",
                    pkg,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Verify path-segment intermediates JBoss LogManager has now
            // materialized are NOT shown — only `pkg` itself and the leaf.
            // Concretely there must be no row whose name strictly starts with
            // `pkg + "."` and isn't the leaf (i.e. no `pkg.foo` intermediate).
            page.waitForFunction(
                    "args => {" +
                            "  const [pkg, leaf] = args;" +
                            "  const names = Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            "    .map(e => e.textContent);" +
                            "  return !names.some(n => n.startsWith(pkg + '.') && n !== leaf);" +
                            "}",
                    new Object[] {pkg, leaf},
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Reset via the row's Reset button.
            page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(pkg))
                    .first()
                    .locator(".kc-ll-row__reset")
                    .click();

            // After the reset, `pkg` becomes a path-segment ghost (state A with
            // descendants) and the synthetic comes back. We assert the synthetic
            // shows up *somewhere* in the DOM rather than pinning row order —
            // observed timing differs across runners (macOS Docker Desktop is
            // notably slower to settle than Linux), and the row-position
            // invariant doesn't add safety beyond the path-segment hiding the
            // earlier "no intermediates" check at line 541 already covers.
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr.is-prospective'))" +
                            ".some(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)",
                    pkg,
                    new Page.WaitForFunctionOptions().setTimeout(NAV_TIMEOUT_MS));
        }
    }

    @Test
    void modifiedOnlyToggle_hidesNonOverriddenRows() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            // Override SELF_LOGGER so we have one row that should remain visible
            // when the filter is on.
            page.locator("#filter").fill(SELF_LOGGER);
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                            ".some(e => e.textContent === name)",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
            page.locator("#rows tr")
                    .filter(new Locator.FilterOptions().setHasText(SELF_LOGGER))
                    .first()
                    .locator("select.kc-ll-row__select")
                    .selectOption("DEBUG");
            page.waitForFunction(
                    "name => Array.from(document.querySelectorAll('#rows tr'))" +
                            ".find(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name)" +
                            "?.classList.contains('is-overridden') === true",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Clear the search filter so we're looking at the full tree, then
            // turn on Modified only. ROOT (state D) and any other non-overridden
            // top-levels should disappear; SELF_LOGGER (now state C) stays.
            page.locator("#filter").fill("");
            page.locator("#modified-only").check();

            page.waitForFunction(
                    "name => {" +
                            "  const visible = Array.from(document.querySelectorAll('#rows tr'))" +
                            "    .filter(tr => tr.offsetParent !== null);" +
                            "  return visible.length > 0 && " +
                            "    visible.every(tr => tr.classList.contains('is-overridden')) && " +
                            "    visible.some(tr => tr.querySelector('.kc-ll-row__name')?.textContent === name);" +
                            "}",
                    SELF_LOGGER,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            // Untoggle: ROOT comes back.
            page.locator("#modified-only").uncheck();
            page.waitForFunction(
                    "() => {" +
                            "  const rootRow = document.querySelectorAll('#rows tr')[0];" +
                            "  return rootRow && rootRow.offsetParent !== null && " +
                            "    rootRow.querySelector('.kc-ll-row__name')?.textContent === 'ROOT';" +
                            "}",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    @Test
    void rootAtBaseline_isNotMarkedOverridden_andHidesReset() {
        // ROOT is set by KC_LOG_LEVEL=info, so its configured level matches the
        // captured baseline (state D). Reset would be a no-op, so the button must
        // stay hidden and the row must not carry the override marker.
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            final Locator rootRow = page.locator("#rows tr").first();
            assertEquals("ROOT", rootRow.locator(".kc-ll-row__name").textContent());
            assertEquals(false, rootRow.evaluate("el => el.classList.contains('is-overridden')"),
                    "ROOT sitting on its boot value must not be flagged as overridden");
            assertEquals("hidden",
                    rootRow.locator(".kc-ll-row__reset").evaluate("el => getComputedStyle(el).visibility"),
                    "Reset must be hidden on a row whose configured level equals its baseline");
        }
    }

    @Test
    void expandAllButton_togglesAllTopLevelRows() {
        try (BrowserContext ctx = newSignedInContext()) {
            final Page page = openLogLevelsPage(ctx);

            final Locator expandAll = page.locator("#expand-all");
            expandAll.click();
            page.waitForFunction(
                    "() => document.getElementById('expand-all').textContent === 'Collapse all'",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
            page.waitForFunction(
                    "() => Array.from(document.querySelectorAll('.kc-ll-row__expand:not([hidden])'))" +
                            ".every(b => b.classList.contains('is-expanded'))",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));

            expandAll.click();
            page.waitForFunction(
                    "() => document.getElementById('expand-all').textContent === 'Expand all'",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
            page.waitForFunction(
                    "() => Array.from(document.querySelectorAll('.kc-ll-row__expand:not([hidden])'))" +
                            ".every(b => !b.classList.contains('is-expanded'))",
                    null,
                    new Page.WaitForFunctionOptions().setTimeout(ACTION_TIMEOUT_MS));
        }
    }

    private static Page openLogLevelsPage(BrowserContext ctx) {
        final Page page = ctx.pages().get(0);
        page.locator("#kc-log-level-nav-item a").click();
        page.waitForURL(url -> url.contains("/log-levels.html"),
                new Page.WaitForURLOptions().setTimeout(NAV_TIMEOUT_MS));
        page.locator("#rows tr").first().waitFor(
                new Locator.WaitForOptions().setTimeout(NAV_TIMEOUT_MS));
        return page;
    }

    private static long rowsWithName(Page page, String name) {
        return ((Number) page.evaluate(
                "name => Array.from(document.querySelectorAll('#rows tr .kc-ll-row__name'))" +
                        ".filter(e => e.textContent === name).length",
                name)).longValue();
    }

    /**
     * The very first login against a freshly started (cold) server can lose a
     * cookie race: log-level-menu.js runs on the pre-auth console shell, and its
     * client probe hits the OIDC auth endpoint concurrently with the console's
     * own authorization redirect. Both responses set a fresh AUTH_SESSION_ID in
     * the still-empty cookie jar; if the probe's response lands last (likely
     * while the server is still JIT-compiling), the submitted login form belongs
     * to the other auth session and Keycloak restarts the flow (LOGIN_ERROR
     * expired_code, restart_after_timeout) and re-renders the login form.
     * Retrying on the re-rendered form succeeds — the cookie jar has settled by
     * then. Real users see one "login timed out" page and click through; the
     * test does the same instead of failing.
     */
    private static final int LOGIN_ATTEMPTS = 3;
    /** Per-attempt wait before checking whether the login flow was restarted. */
    private static final long LOGIN_RESULT_TIMEOUT_MS = 20_000;

    private BrowserContext newSignedInContext() {
        return newSignedInContext(page -> {
        });
    }

    /**
     * @param beforeFirstSubmit test hook, invoked once on the filled-in login form
     *                          before the first submit (used to simulate the cookie race)
     */
    private BrowserContext newSignedInContext(java.util.function.Consumer<Page> beforeFirstSubmit) {
        final BrowserContext ctx = browser.newContext();
        final Page page = ctx.newPage();
        page.navigate(localhostBase() + "/admin/master/console/");

        final Locator navItem = page.locator("#kc-log-level-nav-item");
        final Locator username = page.locator("#username");
        for (int attempt = 1; attempt <= LOGIN_ATTEMPTS; attempt++) {
            username.waitFor(new Locator.WaitForOptions().setTimeout(NAV_TIMEOUT_MS));
            username.fill(sut.keycloak.getAdminUsername());
            page.locator("#password").fill(sut.keycloak.getAdminPassword());
            if (attempt == 1) {
                beforeFirstSubmit.accept(page);
            }
            page.locator("#kc-login").click();

            // Waiting for our injected nav item proves both that the admin console rendered
            // and that our theme's MutationObserver successfully attached.
            try {
                navItem.waitFor(new Locator.WaitForOptions().setTimeout(LOGIN_RESULT_TIMEOUT_MS));
                return ctx;
            }
            catch (TimeoutError e) {
                if (username.isVisible()) {
                    continue; // login flow was restarted (see javadoc above) — retry on the fresh form
                }
                // No login form: we are past authentication, the console is just slow.
                // Keep the original full wait budget before giving up.
                navItem.waitFor(new Locator.WaitForOptions().setTimeout(NAV_TIMEOUT_MS));
                return ctx;
            }
        }
        throw new AssertionError(
                "admin console login did not succeed after " + LOGIN_ATTEMPTS + " attempts"
                        + " (login flow kept being restarted)");
    }
}
