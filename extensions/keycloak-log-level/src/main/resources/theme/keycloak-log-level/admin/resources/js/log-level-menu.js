// Injects a "Log levels" entry into the Keycloak admin console sidebar, in the
// "Configure" section (the same group as Realm settings, Authentication, etc.).
//
// The admin console is a React SPA with no plugin API for menu items, so we DOM-hack:
// watch for the nav to render and append our <li> to the Configure section's list.
// A MutationObserver re-injects whenever React re-renders the nav (e.g. on realm switch).
//
// Selectors are deliberately structural (HTML5 + ARIA) rather than tied to PatternFly
// version classes (`pf-v5-c-nav__*`). The structure relied on — `nav[aria-label] >
// section > ul` — is plain HTML5 and matches both PF v5 and PF v6, so the injection
// survives admin-ui's eventual PF upgrade without code changes. We also avoid matching
// on visible text (e.g. "Configure"), since the admin console may be running in any
// locale; instead we rely on the information-architecture assumption that the last
// section in the global nav is the "Configure" group, which has held across recent
// admin-ui releases. To keep visual styling matched to the surrounding entries, we
// copy class names from a sibling nav item rather than hardcoding any PF class.
//
// To make the UI optional, we first probe the OIDC auth endpoint to confirm the
// `keycloak-log-level-ui` public client exists. If it's missing — which is the
// expected state when an admin pulls in the extension jar without adding the
// client to their realm — the menu item is silently not injected.

const ITEM_ID = "kc-log-level-nav-item";
const FALLBACK_LABEL = "Log levels";
const CLIENT_ID = "keycloak-log-level-ui";
const NAV_SECTION_SELECTOR = "nav[aria-label] section";
const STATE_MODIFIER_CLASS = /(?:^|-)(?:current|active|selected)$/;

/**
 * Pull the localized sidebar label out of messages/messages_<locale>.json,
 * falling back to messages_en.json, and finally to {@link FALLBACK_LABEL} if
 * neither bundle is reachable. Uses {@code env.resourceUrl} so the fetch hits
 * the same theme directory that serves the standalone page.
 */
async function fetchMenuLabel(env) {
    const candidates = [];
    const locale = (navigator.language || "en").split("-")[0].toLowerCase();
    if (locale && locale !== "en") candidates.push(locale);
    candidates.push("en");
    for (const candidate of candidates) {
        try {
            const response = await fetch(`${env.resourceUrl}/messages/messages_${candidate}.json`);
            if (!response.ok) continue;
            const data = await response.json();
            if (data && data.menuLabel) return data.menuLabel;
        } catch {
            // keep trying
        }
    }
    return FALLBACK_LABEL;
}

function getEnvironment() {
    const el = document.getElementById("environment");
    if (!el) return null;
    try {
        return JSON.parse(el.textContent);
    } catch {
        return null;
    }
}

/**
 * Pick the <ul> for the last section in the admin console nav. In the default
 * Keycloak admin console that's the "Configure" section (Realm settings,
 * Authentication, Identity providers, …).
 */
function findConfigureList() {
    const sections = document.querySelectorAll(NAV_SECTION_SELECTOR);
    if (sections.length === 0) return null;
    return sections[sections.length - 1].querySelector("ul");
}

/**
 * Build a class string from a sibling element while stripping state modifiers
 * (e.g. `pf-m-current`, `is-active`) so our injected entry doesn't render as if
 * it were the currently-selected route. Returns an empty string when the
 * sibling is missing or has no classes — admin-ui will still display the entry,
 * just without PF visual treatment.
 */
function copyStableClasses(sibling) {
    if (!sibling) return "";
    return Array.from(sibling.classList)
        .filter(c => !STATE_MODIFIER_CLASS.test(c))
        .join(" ");
}

/**
 * Build a fresh PKCE S256 challenge from a random verifier. The verifier is
 * discarded immediately — the probe never exchanges the auth code, so the
 * verifier exists only briefly in this function's stack frame. Hardcoding a
 * known challenge (e.g. the RFC 7636 example) would weaken PKCE's protection:
 * an attacker who could intercept the probe's auth code could trivially
 * exchange it because the corresponding verifier would be public knowledge.
 */
async function makeProbeChallenge() {
    const verifier = window.crypto.getRandomValues(new Uint8Array(32));
    const digest = await window.crypto.subtle.digest("SHA-256", verifier);
    return bytesToBase64Url(new Uint8Array(digest));
}

function bytesToBase64Url(bytes) {
    let binary = "";
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/**
 * Returns true iff the OIDC auth endpoint accepts our client and redirect URI.
 *
 * Probe: prompt=none authorization request with redirect: 'manual'.
 * - Valid client + valid redirect URI ⇒ Keycloak issues a 302 (either with the
 *   auth code, or with error=login_required). Fetch returns type 'opaqueredirect'.
 * - Missing or misconfigured client ⇒ Keycloak returns a 400 HTML error page
 *   directly, never redirecting to an unverified URI. type === 'basic'.
 *
 * The recommended client config enforces PKCE (S256), so the probe sends a
 * well-formed code_challenge to satisfy parameter validation. Without it the
 * probe would still work (Keycloak still 302s on PKCE failure, satisfying the
 * opaqueredirect check) but would emit a noisy WARN-level LOGIN_ERROR on
 * every admin-console page load. Web Crypto is required and assumed present —
 * the standalone page's real auth flow has the same dependency, and missing
 * it would mean PKCE auth wouldn't work at all.
 */
async function isClientConfigured(env, redirectUri) {
    const challenge = await makeProbeChallenge();
    const params = new URLSearchParams({
        response_type: "code",
        client_id: CLIENT_ID,
        redirect_uri: redirectUri,
        scope: "openid",
        prompt: "none",
        code_challenge: challenge,
        code_challenge_method: "S256",
    });
    const url = `${env.authServerUrl}/realms/master/protocol/openid-connect/auth?${params.toString()}`;
    try {
        const response = await fetch(url, {
            method: "GET",
            redirect: "manual",
            credentials: "include",
        });
        return response.type === "opaqueredirect";
    } catch {
        return false;
    }
}

function ensureMenuItem(redirectUri, label) {
    if (document.getElementById(ITEM_ID)) return;

    const list = findConfigureList();
    if (!list) return;

    // Copy the surrounding entries' classes so we follow whatever PatternFly version
    // admin-ui currently uses. State modifiers are stripped so we don't accidentally
    // render as the active route.
    const siblingItem = list.querySelector("li");
    const siblingLink = siblingItem ? siblingItem.querySelector("a") : null;

    const li = document.createElement("li");
    li.id = ITEM_ID;
    li.className = copyStableClasses(siblingItem);

    const link = document.createElement("a");
    link.href = redirectUri;
    link.className = copyStableClasses(siblingLink);
    link.textContent = label;

    li.appendChild(link);
    list.appendChild(li);
}

(async function start() {
    const env = getEnvironment();
    if (!env) return;
    // Absolute redirect URI: the standalone page constructs it the same way
    // (`window.location.origin + pathname`), so the probe and the real auth flow
    // see the exact same redirect_uri value when matching against the client's
    // configured pattern.
    const pageUrl = new URL(env.resourceUrl + "/log-levels.html", window.location.origin).href;
    if (!(await isClientConfigured(env, pageUrl))) return;

    const label = await fetchMenuLabel(env);
    ensureMenuItem(pageUrl, label);
    new MutationObserver(() => ensureMenuItem(pageUrl, label))
        .observe(document.body, { childList: true, subtree: true });
})();
