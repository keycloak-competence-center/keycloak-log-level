Keycloak Dynamic Log Level extension
===

The keycloak-log-level extension adds a small REST resource that lets a master-realm admin inspect and change Keycloak's log levels at runtime, without restarting the server. Changes made through this endpoint are not persisted &mdash; they are lost on restart. For permanent settings, use `log-level=...` in `keycloak.conf` or the matching env var.

### Installation

To install the extension, add the jar to the Keycloak server's `providers` directory.

#### Prerequisites

- Java 21 or later (only needed when building from source)

#### From Maven Central

The extension is published to Maven Central. Download the jar directly:

```shell
curl -O https://repo.maven.apache.org/maven2/com/inventage/keycloak/log-level/keycloak-log-level/<VERSION>/keycloak-log-level-<VERSION>.jar
```

Or add it as a dependency in your build:

```xml
<dependency>
    <groupId>com.inventage.keycloak.log-level</groupId>
    <artifactId>keycloak-log-level</artifactId>
    <version>VERSION</version>
</dependency>
```

Copy the jar to the Keycloak providers directory:

```shell
cp keycloak-log-level-<VERSION>.jar <KEYCLOAK_HOME>/providers/
```

#### Building from source

Clone the repository and build the extension module using the included Maven wrapper:

```shell
./mvnw clean package -pl extensions/keycloak-log-level -am
```

This builds the jar at `extensions/keycloak-log-level/target/keycloak-log-level-<VERSION>.jar`. Copy it to the Keycloak providers directory:

```shell
cp extensions/keycloak-log-level/target/keycloak-log-level-<VERSION>.jar <KEYCLOAK_HOME>/providers/
```

#### Docker

When building a custom Keycloak Docker image, copy the jar into the providers directory in your Dockerfile:

```dockerfile
COPY keycloak-log-level-<VERSION>.jar /opt/keycloak/providers/
```

After adding the provider, rebuild Keycloak to pick up the extension:

```shell
/opt/keycloak/bin/kc.sh build
```

### Endpoint

The provider registers a `RealmResourceProvider` with id `logging`, mounted at:

```
/realms/master/logging
```

The endpoint is intentionally only accepted on the `master` realm, since logger configuration is JVM-global.

#### Authentication

All operations require a bearer token for a user that holds the `admin` role of the `master` realm. The same token used for the Keycloak admin REST API works.

#### Operations

| Method | Path                  | Body                  | Description                                                                                  |
|--------|-----------------------|-----------------------|----------------------------------------------------------------------------------------------|
| GET    | `/`                   |                       | Lists all known loggers as `{name: {level, configuredLevel, baselineLevel}}`. `level` is the effective level (walked up the parent chain); `configuredLevel` is `null` when the logger inherits from its parent; `baselineLevel` is the level captured at startup (see `DELETE` below) and is `null` if the logger was inheriting at that point. The root logger is reported as `ROOT`. |
| GET    | `/{logger}`           |                       | Returns the configured, effective, and baseline level for one logger. Use `ROOT` for the root logger.   |
| PUT    | `/{logger}`           | `{"level": "DEBUG"}`  | Sets the level. Standard `java.util.logging` levels: `OFF, SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST, ALL`. JBoss aliases (`ERROR`, `WARN`, `DEBUG`, `TRACE`) also work because the JBoss LogManager is in use. |
| DELETE | `/{logger}`           |                       | Restores the logger to its **startup baseline** — the level captured by the provider's `postInit` snapshot, which reflects whatever was applied via `KC_LOG_LEVEL` / `quarkus.log.category."<name>".level`. For loggers that were inheriting at startup (or were created later), this clears the configured level so the logger inherits from its parent again. The change is not persisted. |

#### Multi-replica deployments

Log level changes are **ephemeral** — they live in the in-memory `LogContext` of each replica's JVM and are lost on restart. For permanent, cluster-wide log levels, configure them at startup via `KC_LOG_LEVEL` (or `quarkus.log.category."<name>".level`) so every replica sees the same configuration on boot. The runtime UI and REST endpoints are intended for short-lived diagnostic tweaks.

Within a running cluster, the extension broadcasts every `PUT` and `DELETE` through Keycloak's [`ClusterProvider`](https://github.com/keycloak/keycloak/blob/main/server-spi-private/src/main/java/org/keycloak/cluster/ClusterProvider.java) so the change fans out to the other replicas. Behavior across deployment topologies:

| Topology                                  | Behavior                                                                                                                                                                                                                                          |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Single node                               | Local change only. No broadcast needed.                                                                                                                                                                                                          |
| N replicas, embedded Infinispan (default) | Broadcast via JGroups — every replica in the cluster applies the change. Restart of any replica reverts that replica to its startup baseline.                                                                                                     |
| N replicas, remote Infinispan             | Broadcast via the remote `work` cache. Replicas that are normally connected receive the event; a replica that was disconnected from the Infinispan server when the broadcast happened, or that joins later, **will not catch up** and stays at its startup baseline until something else triggers a re-broadcast. |
| Multi-site deployments                    | The cluster broadcast covers a single site. Apply the change once per site, or rely on `KC_LOG_LEVEL` (which every site honors at startup) for site-wide persistent settings. |
| Custom non-Infinispan cache provider      | Out of scope. Keycloak's broadcast SPI assumes Infinispan; a custom provider would need to ship its own `ClusterProvider` implementation.                                                                                                         |

Since this is a diagnostic tool, missing-event corner cases (a replica that briefly drops off the cluster) are expected and acceptable — the operator can always re-issue the change. For anything that needs to be durable, set it via `KC_LOG_LEVEL`.

#### Examples

A runnable set of requests — token grab via password grant, list/get/set/reset, and the 400/401/403 error paths — lives at [`.docs/keycloak.http`](https://github.com/keycloak-competence-center/keycloak-log-level/blob/main/.docs/keycloak.http). It works out of the box with the JetBrains HTTP Client and the VS Code REST Client extension; the requests target `http://localhost:8080` and use the local-dev `temp-admin` / `admin` credentials by default.

### Admin console UI

The same jar also ships an admin theme named `keycloak-log-level` that injects a **Log levels** entry into the master-realm admin console sidebar. Clicking it opens a standalone page (also bundled in the jar) that lists every logger, lets you change a level via a dropdown, and reset back to the startup baseline via a button — all backed by the REST endpoint above. The reset button shows the level it will install (e.g. `Reset → INFO`) so it's clear what reset does for a logger that was pre-configured via `KC_LOG_LEVEL`.

To enable the UI, two things are needed in the master realm:

1. **Set the admin theme** on the master realm to `keycloak-log-level`. Either via the admin console (Realm settings → Themes → Admin theme) or via realm import:
    ```json
    {
      "realm": "master",
      "adminTheme": "keycloak-log-level"
    }
    ```
2. **Add a public OIDC client** the page authenticates against. The client uses the OAuth 2.0 authorization-code flow with PKCE (S256) — no secret is needed, but the client must exist:
    ```json
    {
      "clientId": "keycloak-log-level-ui",
      "name": "Keycloak log-level extension UI",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": true,
      "directAccessGrantsEnabled": false,
      "protocol": "openid-connect",
      "redirectUris": ["/resources/*"],
      "webOrigins": ["+"],
      "attributes": {
        "pkce.code.challenge.method": "S256"
      }
    }
    ```

   The `redirectUris` pattern matches Keycloak's theme-resource URL (`/resources/<version>/admin/keycloak-log-level/log-levels.html`); only Keycloak itself can serve content under `/resources/*`, so the wildcard is safe.

The menu entry is only injected when **both** of these are in place — the theme provides the script that renders the entry, and on first load the script probes the OIDC auth endpoint to confirm `keycloak-log-level-ui` is configured. If either is missing the entry stays hidden, which keeps the UI strictly opt-in for consumers who only want the REST API. Once both are configured, anyone with the master-realm `admin` role sees the **Log levels** entry under the *Configure* section of the admin sidebar.

#### Customizing or replacing the UI

Two override paths, in order of precedence:

- **Folder theme** at `<KEYCLOAK_HOME>/themes/keycloak-log-level/admin/...`. Keycloak's `FolderThemeProvider` runs before `JarThemeProvider`, so any file you place there shadows the bundled one with the same path. Useful for one-off CSS or label tweaks.
- **A new theme that inherits from `keycloak-log-level`**, e.g. `theme/my-org/admin/theme.properties` containing `parent=keycloak-log-level`. Override only the files you need (e.g. a different menu label or a corporate-branded page) and inherit the rest. Set `adminTheme=my-org` on the realm.

The page styling lives in `resources/css/log-levels.css` and is fully self-contained (it does not depend on any PatternFly version). All colours are CSS custom properties under `:root` (`--kc-ll-bg`, `--kc-ll-surface`, `--kc-ll-text`, `--kc-ll-accent`, …) with a `prefers-color-scheme: dark` block, so a consumer who only wants to retheme can redefine the variables on `:root` instead of replacing the whole stylesheet.

#### Translations

All user-facing strings — page chrome, table headers, status messages, aria-labels, the *(inherited)* suffix, and the sidebar menu label — are pulled from `resources/messages/messages_<locale>.json`. The locale is taken from the `?lang=xx` query parameter if present, otherwise from the language part of `navigator.language`. The English bundle is always loaded as a base, so a localized bundle only needs to override the keys it wants to translate.

To add a translation, drop a new bundle into your overlay theme at `theme/<your-theme>/admin/resources/messages/messages_<locale>.json`, including any subset of the keys defined in [`messages_en.json`](src/main/resources/theme/keycloak-log-level/admin/resources/messages/messages_en.json). Missing keys fall back to English. Status-message keys (`setOk`, `setFailed`, `loadFailed`, …) use `{name}`, `{level}`, and `{error}` placeholders — keep the placeholder names as-is.

### How it works

Keycloak runs on Quarkus, which uses the JBoss LogManager as the JVM `LogManager`. The provider sets levels through the standard `java.util.logging.Logger#setLevel` API; under the JBoss LogManager those calls update the live category configuration so existing handlers immediately respect the new threshold.
