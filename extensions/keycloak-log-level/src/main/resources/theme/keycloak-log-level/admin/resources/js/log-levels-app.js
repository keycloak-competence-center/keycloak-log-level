// Standalone log-levels admin page.
//
// Authenticates against the master realm using the OIDC authorization-code flow
// with PKCE (public client `keycloak-log-level-ui`), then drives the
// /realms/master/logging REST endpoint exposed by the keycloak-log-level
// extension. Keycloak doesn't ship keycloak.js as a server-served adapter, so
// the auth dance is implemented inline (see createAuth() at the bottom).
//
// Strings are pulled from messages/messages_<locale>.json with English fallback;
// see translate() and loadMessages() at the bottom of the file. A custom theme
// (parent=keycloak-log-level) can drop an additional messages_<locale>.json into
// the same directory to add or override translations.

const API_BASE = "/realms/master/logging";
const REALM = "master";
const CLIENT_ID = "keycloak-log-level-ui";
const TOKEN_REFRESH_LEEWAY_MS = 30_000;
const MESSAGES_DIR = "messages";

const $rows = document.getElementById("rows");
const $table = document.getElementById("loggers");
const $filter = document.getElementById("filter");
const $filterClear = document.getElementById("filter-clear");
const $reload = document.getElementById("reload");
const $expandAll = document.getElementById("expand-all");
const $modifiedOnly = document.getElementById("modified-only");
const $status = document.getElementById("status");
const $statusText = document.getElementById("status-text");
const rowTpl = document.getElementById("row-tpl");

// Conservative validation for the synthetic-row affordance: java.util.logging
// itself accepts almost any string, but we don't want to offer "create" rows
// for whitespace-only or otherwise malformed input. Permit letters, digits,
// dots, dashes, underscores, and dollar signs (covers Java class/package names
// plus the inner-class separator).
const VALID_LOGGER_NAME = /^[a-zA-Z0-9_.\-$]+$/;

let loggers = {};
let statusToken = 0;
let allExpanded = false;

const auth = createAuth({
    serverBase: window.location.origin,
    realm: REALM,
    clientId: CLIENT_ID,
    redirectUri: window.location.origin + window.location.pathname,
});

function setStatus(text, kind) {
    statusToken += 1;
    const myToken = statusToken;
    if (!text) {
        $status.hidden = true;
        $status.className = "kc-ll-status";
        $statusText.textContent = "";
        return;
    }
    $statusText.textContent = text;
    let modifier = "";
    if (kind === "ok") modifier = " kc-ll-status--success";
    else if (kind === "err") modifier = " kc-ll-status--danger";
    $status.className = "kc-ll-status" + modifier;
    $status.hidden = false;
    if (kind === "ok") {
        setTimeout(() => {
            if (statusToken === myToken) {
                $status.hidden = true;
                $status.className = "kc-ll-status";
                $statusText.textContent = "";
            }
        }, 3000);
    }
}

async function api(method, path, body) {
    const token = await auth.getValidAccessToken();
    const headers = { Authorization: `Bearer ${token}`, Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(API_BASE + path, {
        method,
        headers,
        body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    if (!response.ok) {
        let detail = "";
        try {
            const parsed = await response.json();
            detail = parsed.error || parsed.errorMessage || "";
        } catch {
            // ignore
        }
        throw new Error(`${response.status} ${response.statusText}${detail ? ": " + detail : ""}`);
    }
    if (response.status === 204) return null;
    return response.json();
}

async function loadLoggers() {
    try {
        setStatus(t("loading"));
        loggers = await api("GET", "");
        render();
        setStatus("");
    } catch (e) {
        setStatus(t("loadFailed", { error: e.message }), "err");
    }
}

/**
 * Group loggers into a one-level tree where each logger with a configured level
 * (plus the always-shown ROOT) becomes a top-level entry; loggers without a
 * configured level become children of their nearest configured ancestor by
 * dotted-name hierarchy, falling back to ROOT.
 *
 * Returns: [{ name, info, children: [name, …] }, …]  with top-levels alphabetical
 * (ROOT first), children alphabetical.
 */
function isConfigured(info) {
    // Keycloak's ObjectMapper omits null fields by default, so configuredLevel
    // is undefined (not explicit null) for loggers that inherit from a parent.
    return info.configuredLevel != null;
}

/**
 * True for loggers that exist purely as a dotted-path waypoint to other loggers,
 * with no level state of their own (configuredLevel and baselineLevel both
 * absent) AND at least one descendant in the map. Examples: when an operator
 * sets a level on `com.inventage`, JBoss LogManager materializes
 * `com.inventage.keycloak`, `com.inventage.keycloak.loglevel`, etc., as nodes
 * the GET response surfaces. They have no actionable state and clutter both
 * search results and tree expansion.
 *
 * State-A leaves (no descendants — typically class-named loggers) are NOT
 * path-segments and remain visible. After resetting a synthetic-promoted
 * logger like `com.inventage`, that logger becomes a path-segment itself
 * (descendants still exist, level cleared) and gets hidden — replaced by a
 * fresh synthetic row when the operator searches for it.
 */
function isPathSegment(name, info) {
    if ((info.configuredLevel ?? null) !== null) return false;
    if ((info.baselineLevel ?? null) !== null) return false;
    const prefix = name + ".";
    return Object.keys(loggers).some(other => other.startsWith(prefix));
}

function buildTree() {
    const names = Object.keys(loggers);
    const tops = new Set(names.filter(n => isConfigured(loggers[n]) || n === "ROOT"));

    const childrenByParent = new Map();
    for (const top of tops) childrenByParent.set(top, []);

    function nearestConfiguredAncestor(name) {
        if (name === "ROOT") return null;
        let n = name;
        while (n.includes(".")) {
            n = n.substring(0, n.lastIndexOf("."));
            if (tops.has(n)) return n;
        }
        return tops.has("ROOT") ? "ROOT" : null;
    }

    for (const name of names) {
        if (tops.has(name)) continue;
        if (isPathSegment(name, loggers[name])) continue;
        const parent = nearestConfiguredAncestor(name);
        if (parent !== null) childrenByParent.get(parent).push(name);
    }

    const sortedTops = [...tops].sort((a, b) => {
        if (a === "ROOT") return -1;
        if (b === "ROOT") return 1;
        return a.localeCompare(b);
    });

    return sortedTops.map(name => ({
        name,
        info: loggers[name],
        children: childrenByParent.get(name).sort(),
    }));
}

/**
 * Walk the dotted-name chain from {@code name} upward, returning the level of
 * the nearest configured ancestor in the loggers map; falls back to ROOT's
 * effective level, then null if the map has nothing useful (very unusual —
 * ROOT is always present).
 */
function inheritedLevelFor(name) {
    let n = name;
    while (n.includes(".")) {
        n = n.substring(0, n.lastIndexOf("."));
        if (loggers[n] && loggers[n].configuredLevel) return loggers[n].configuredLevel;
    }
    if (loggers.ROOT) return loggers.ROOT.level;
    return null;
}

/**
 * Predict the level a logger will have after {@code DELETE} (the "reset" gesture).
 *
 * - If the server captured a startup baseline for this logger, that's what reset
 *   will install; that level is the post-reset level directly.
 * - Otherwise reset clears the configured level, so the logger inherits its
 *   level from the parent chain — see {@link inheritedLevelFor}.
 */
function postResetLevel(name, info) {
    if (info.baselineLevel) return info.baselineLevel;
    return inheritedLevelFor(name);
}

function buildRow(name, info, opts = {}) {
    const tr = rowTpl.content.firstElementChild.cloneNode(true);
    tr.querySelector(".kc-ll-row__name").textContent = name;
    const $level = tr.querySelector(".kc-ll-row__level");
    $level.textContent = info.level;
    if (opts.inherited) tr.classList.add("is-inherited");
    if (opts.parent) tr.dataset.parent = opts.parent;

    // A row is "overridden" when its current configured level differs from the
    // level captured at startup. Both sides may be undefined (Jackson omits null
    // fields) — `undefined !== undefined` is false, so a never-configured logger
    // (state A) and a logger sitting on its boot value (state D) are correctly
    // not flagged. The class drives both the Reset button visibility and the
    // amber level-text color in CSS.
    const isOverridden = (info.configuredLevel ?? null) !== (info.baselineLevel ?? null);
    if (isOverridden) {
        tr.classList.add("is-overridden");
        $level.title = t("overriddenTooltip");
    }
    // Prospective: the row corresponds to a category the operator typed into
    // search but no Logger has been created for yet. The dropdown still works
    // — the server's PUT handler creates the category lazily — but we tag the
    // row so CSS can give it a subtle "this is a new category" treatment, and
    // so Playwright can assert on it.
    if (opts.prospective) {
        tr.classList.add("is-prospective");
        tr.querySelector(".kc-ll-row__name").title = t("prospectiveTooltip");
    }

    const select = tr.querySelector(".kc-ll-row__select");
    select.addEventListener("change", () => onSetLevel(name, select.value, select));
    const $reset = tr.querySelector(".kc-ll-row__reset");
    const resetTarget = postResetLevel(name, info);
    $reset.textContent = resetTarget
        ? t("resetButtonWithLevel", { level: resetTarget })
        : t("resetButton");
    $reset.addEventListener("click", () => onReset(name));

    if (opts.expandable) {
        const $expand = tr.querySelector(".kc-ll-row__expand");
        const $cell = tr.querySelector(".kc-ll-row__name-cell");
        $expand.hidden = false;
        $cell.classList.add("is-expandable");
        let expanded = false;

        // Single click handler on the cell. The chevron is a focusable button
        // for keyboard users; its native click bubbles up here, so we don't
        // need a separate listener (which would double-fire).
        $cell.addEventListener("click", () => {
            expanded = !expanded;
            $expand.classList.toggle("is-expanded", expanded);
            $expand.setAttribute("aria-label", expanded ? t("collapseAria") : t("expandAria"));
            if (expanded) {
                let prev = tr;
                for (const childName of opts.children) {
                    const childRow = buildRow(childName, loggers[childName], { inherited: true, parent: name });
                    prev.after(childRow);
                    prev = childRow;
                }
            } else {
                $rows.querySelectorAll('tr[data-parent]').forEach(r => {
                    if (r.dataset.parent === name) r.remove();
                });
            }
        });
    }

    return tr;
}

function render() {
    const q = $filter.value.toLowerCase().trim();
    $rows.replaceChildren();
    $expandAll.hidden = !!q;

    if (q) {
        // Active filter: flat list, search across configured + inherited so the
        // user can find any logger by name and set a level on it. Path-segment
        // ghosts (state-A intermediates with descendants) are hidden — they're
        // not actionable and only add noise to results.
        const matches = Object.keys(loggers)
            .filter(name => name.toLowerCase().includes(q))
            .filter(name => !isPathSegment(name, loggers[name]))
            .sort();
        // Whenever the typed text is a syntactically valid logger name that
        // isn't a real entry in the map (or is a path-segment ghost), prepend
        // a "prospective" row offering to set a level on it. This appears
        // alongside any substring matches — e.g. typing `com.inventage`
        // surfaces a creatable row for the package even though leaf classes
        // under it match the substring filter. The server creates the
        // category lazily on PUT, so picking a level from the dropdown
        // actually does something. Treating path-segments as "absent" here
        // also gives a clean post-reset UX: a logger you just reset to
        // inherited (no baseline, no configured level) becomes a path-segment
        // and the synthetic re-appears, mirroring the state before you
        // touched it.
        const raw = $filter.value.trim();
        const existing = loggers[raw];
        if (VALID_LOGGER_NAME.test(raw) && (!existing || isPathSegment(raw, existing))) {
            const inherited = inheritedLevelFor(raw);
            $rows.appendChild(buildRow(raw, { level: inherited ?? "INFO" },
                    { inherited: true, prospective: true }));
        }
        for (const name of matches) {
            const info = loggers[name];
            $rows.appendChild(buildRow(name, info, { inherited: !isConfigured(info) }));
        }
        $table.hidden = $rows.childElementCount === 0;
        return;
    }

    // No filter: tree rooted at configured loggers (+ ROOT).
    const tree = buildTree();
    for (const node of tree) {
        $rows.appendChild(buildRow(node.name, node.info, {
            expandable: node.children.length > 0,
            children: node.children,
        }));
    }
    $table.hidden = tree.length === 0;

    if (allExpanded) {
        // Re-apply expand-all state across re-renders so a set/reset action
        // doesn't quietly collapse everything the user had opened.
        for (const btn of $rows.querySelectorAll(".kc-ll-row__expand:not([hidden])")) {
            if (!btn.classList.contains("is-expanded")) btn.click();
        }
    }
}

function setExpandAllState(expanded) {
    allExpanded = expanded;
    $expandAll.textContent = expanded ? t("collapseAll") : t("expandAll");
    for (const btn of $rows.querySelectorAll(".kc-ll-row__expand:not([hidden])")) {
        const isExpanded = btn.classList.contains("is-expanded");
        if (expanded !== isExpanded) btn.click();
    }
}

async function onSetLevel(name, level, select) {
    if (!level) return;
    try {
        await api("PUT", "/" + encodeURIComponent(name), { level });
        setStatus(t("setOk", { name, level }), "ok");
        await loadLoggers();
    } catch (e) {
        setStatus(t("setFailed", { name, error: e.message }), "err");
    } finally {
        select.value = "";
    }
}

async function onReset(name) {
    try {
        await api("DELETE", "/" + encodeURIComponent(name));
        setStatus(t("resetOk", { name }), "ok");
        await loadLoggers();
    } catch (e) {
        setStatus(t("resetFailed", { name, error: e.message }), "err");
    }
}

$filter.addEventListener("input", () => {
    // In filter mode the tree is flattened, so the expand-all button is
    // meaningless — hide it. Restoring on filter clear is handled in render.
    $expandAll.hidden = !!$filter.value.trim();
    $filterClear.hidden = !$filter.value;
    render();
});
$filterClear.addEventListener("click", () => {
    $filter.value = "";
    $filterClear.hidden = true;
    $expandAll.hidden = false;
    render();
    $filter.focus();
});
$reload.addEventListener("click", loadLoggers);
$expandAll.addEventListener("click", () => setExpandAllState(!allExpanded));
// Modified-only toggle: hides non-overridden rows via a class on the table so
// CSS can do the filtering with `display: none`. Composes naturally with the
// search filter above; nothing else needs to know about it.
$modifiedOnly.addEventListener("change", () => {
    $table.classList.toggle("is-modified-only", $modifiedOnly.checked);
});

(async function init() {
    try {
        await loadMessages();
        applyStaticTranslations();
        await auth.initialize();
        await loadLoggers();
    } catch (e) {
        setStatus(t("initFailed", { error: e && e.message ? e.message : String(e) }), "err");
    }
})();

// ─────────────────────────────────────────────────────────────────────────────
// i18n
//
// Locale resolution: ?lang=xx query parameter wins; otherwise the language part
// of navigator.language. The English bundle is always loaded as a base so any
// missing keys in a localized bundle fall back to English. Bundle fetch errors
// are non-fatal — translate() returns the English default (or the key itself
// if even that fails to load), keeping the page usable.

const messages = {};

function detectLocale() {
    const params = new URLSearchParams(window.location.search);
    const override = params.get("lang");
    if (override) return override.split("-")[0].toLowerCase();
    return (navigator.language || "en").split("-")[0].toLowerCase();
}

async function fetchBundle(locale) {
    try {
        const response = await fetch(`${MESSAGES_DIR}/messages_${locale}.json`);
        if (!response.ok) return null;
        return await response.json();
    } catch {
        return null;
    }
}

async function loadMessages() {
    const en = await fetchBundle("en") || {};
    Object.assign(messages, en);
    const locale = detectLocale();
    if (locale !== "en") {
        const localized = await fetchBundle(locale);
        if (localized) Object.assign(messages, localized);
    }
}

function t(key, params) {
    let s = messages[key];
    if (s === undefined) return key;
    if (params) {
        for (const [k, v] of Object.entries(params)) {
            s = s.replaceAll(`{${k}}`, v);
        }
    }
    return s;
}

function applyStaticTranslations() {
    document.title = t("pageTitle");
    for (const el of document.querySelectorAll("[data-i18n]")) {
        el.textContent = t(el.dataset.i18n);
    }
    for (const el of document.querySelectorAll("[data-i18n-placeholder]")) {
        el.placeholder = t(el.dataset.i18nPlaceholder);
    }
    for (const el of document.querySelectorAll("[data-i18n-aria-label]")) {
        el.setAttribute("aria-label", t(el.dataset.i18nAriaLabel));
    }
    // Push the localized "(inherited)" suffix into a CSS custom property so the
    // ::after pseudo-element on inherited rows can pick it up without duplicating
    // text nodes in the DOM. Quote-wrap as a CSS string and escape any embedded
    // double quotes.
    const suffix = t("inheritedSuffix");
    document.documentElement.style.setProperty(
        "--kc-ll-inherited-suffix", `"${suffix.replace(/"/g, '\\"')}"`);
}

// ─────────────────────────────────────────────────────────────────────────────
// Inline OIDC code+PKCE adapter

function createAuth(config) {
    const { serverBase, realm, clientId, redirectUri } = config;
    const authEndpoint = `${serverBase}/realms/${realm}/protocol/openid-connect/auth`;
    const tokenEndpoint = `${serverBase}/realms/${realm}/protocol/openid-connect/token`;
    const VERIFIER_KEY = "kc-log-level-pkce-verifier";

    let accessToken = null;
    let refreshToken = null;
    let accessExpiresAt = 0;

    async function initialize() {
        const params = new URLSearchParams(window.location.search);
        const code = params.get("code");
        if (code) {
            const verifier = sessionStorage.getItem(VERIFIER_KEY);
            sessionStorage.removeItem(VERIFIER_KEY);
            // Strip code/state from URL before doing anything else.
            window.history.replaceState({}, "", window.location.pathname);
            await exchangeCode(code, verifier);
            return;
        }
        await redirectToAuth();
        // The redirect leaves this page; the await below never resolves.
        await new Promise(() => {});
    }

    async function redirectToAuth() {
        // S256 PKCE is required by the realm client config. crypto.subtle is only
        // exposed in secure contexts (HTTPS, localhost, or 127.0.0.1) — production
        // deployments will be behind HTTPS, and tests target http://localhost:<port>.
        if (!window.crypto || !window.crypto.subtle) {
            throw new Error(
                "Web Crypto (window.crypto.subtle) is unavailable: serve this page over HTTPS " +
                "or via a localhost origin so PKCE S256 can run.");
        }
        const verifier = randomString(64);
        const challenge = await pkceChallenge(verifier);
        sessionStorage.setItem(VERIFIER_KEY, verifier);
        const params = new URLSearchParams({
            client_id: clientId,
            redirect_uri: redirectUri,
            response_type: "code",
            scope: "openid",
            code_challenge: challenge,
            code_challenge_method: "S256",
        });
        window.location.assign(`${authEndpoint}?${params.toString()}`);
    }

    async function exchangeCode(code, verifier) {
        if (!verifier) {
            throw new Error("PKCE verifier missing from sessionStorage; cannot complete code exchange.");
        }
        const body = new URLSearchParams({
            grant_type: "authorization_code",
            client_id: clientId,
            code,
            redirect_uri: redirectUri,
            code_verifier: verifier,
        });
        const response = await fetch(tokenEndpoint, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString(),
        });
        if (!response.ok) {
            throw new Error(`Token exchange failed: ${response.status} ${await response.text()}`);
        }
        applyTokenResponse(await response.json());
    }

    async function refreshAccessToken() {
        if (!refreshToken) {
            await redirectToAuth();
            await new Promise(() => {});
            return;
        }
        const body = new URLSearchParams({
            grant_type: "refresh_token",
            client_id: clientId,
            refresh_token: refreshToken,
        });
        const response = await fetch(tokenEndpoint, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString(),
        });
        if (!response.ok) {
            // Refresh failed (token expired/revoked) — fall back to a fresh redirect.
            await redirectToAuth();
            await new Promise(() => {});
            return;
        }
        applyTokenResponse(await response.json());
    }

    function applyTokenResponse(payload) {
        accessToken = payload.access_token;
        refreshToken = payload.refresh_token || refreshToken;
        accessExpiresAt = Date.now() + (payload.expires_in || 60) * 1000;
    }

    async function getValidAccessToken() {
        if (!accessToken || Date.now() >= accessExpiresAt - TOKEN_REFRESH_LEEWAY_MS) {
            await refreshAccessToken();
        }
        return accessToken;
    }

    return { initialize, getValidAccessToken };
}

function randomString(length) {
    const bytes = new Uint8Array(length);
    crypto.getRandomValues(bytes);
    const alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    let out = "";
    for (const b of bytes) out += alphabet[b % alphabet.length];
    return out;
}

async function pkceChallenge(verifier) {
    const data = new TextEncoder().encode(verifier);
    const hash = await crypto.subtle.digest("SHA-256", data);
    return base64UrlEncode(new Uint8Array(hash));
}

function base64UrlEncode(bytes) {
    let binary = "";
    for (const b of bytes) binary += String.fromCharCode(b);
    return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
