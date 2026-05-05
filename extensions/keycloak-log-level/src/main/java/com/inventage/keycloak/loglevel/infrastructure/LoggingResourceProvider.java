package com.inventage.keycloak.loglevel.infrastructure;

import com.inventage.keycloak.loglevel.application.AuditPublisher;
import com.inventage.keycloak.loglevel.application.BaselineRegistry;
import com.inventage.keycloak.loglevel.application.ClusterBroadcaster;
import com.inventage.keycloak.loglevel.application.LogLevelService;
import com.inventage.keycloak.loglevel.application.LoggerInfo;
import com.inventage.keycloak.loglevel.application.LoggerRegistry;
import com.inventage.keycloak.loglevel.domain.LoggerName;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.services.managers.AppAuthManager.BearerTokenAuthenticator;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;
import org.keycloak.services.resource.RealmResourceProvider;

import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;

/**
 * REST resource for inspecting and changing JBoss / java.util.logging log
 * levels at runtime. Mounted at {@code /realms/{realm}/logging}.
 *
 * <p>Thin adapter — every request authenticates the caller, builds a
 * {@link LogLevelService} bound to that context, and delegates. The service
 * lives in the application layer and has no Keycloak knowledge.
 */
public class LoggingResourceProvider implements RealmResourceProvider {

    private static final Logger LOG = Logger.getLogger(LoggingResourceProvider.class);

    private static final String MASTER_REALM = "master";
    private static final String MASTER_ADMIN_ROLE = "admin";

    private final KeycloakSession session;
    private final LoggerRegistry loggers;
    private final BaselineRegistry baselines;

    public LoggingResourceProvider(KeycloakSession session,
                                   LoggerRegistry loggers,
                                   BaselineRegistry baselines) {
        this.session = session;
        this.loggers = loggers;
        this.baselines = baselines;
    }

    @Override
    public Object getResource() {
        return this;
    }

    @Override
    public void close() {
    }

    /**
     * List all known loggers with their effective, configured, and baseline levels.
     *
     * @throws NotAuthorizedException if no bearer token was presented
     * @throws ForbiddenException     if not on the master realm or the caller lacks the {@code admin} role
     */
    @GET
    @Produces(APPLICATION_JSON)
    public Map<String, LoggerInfo> list() {
        return serviceFor(requireMasterAdmin()).list();
    }

    /**
     * Get the levels for a single logger. Pass {@value LoggerName#ROOT_ALIAS}
     * for the unnamed root logger.
     */
    @GET
    @Path("{logger}")
    @Produces(APPLICATION_JSON)
    public Map<String, String> get(@PathParam("logger") String loggerName) {
        return serviceFor(requireMasterAdmin()).get(LoggerName.parse(loggerName));
    }

    /**
     * Set the level for a single logger. Accepts standard {@code java.util.logging}
     * names (case-insensitive) plus the JBoss aliases ({@code DEBUG}, {@code TRACE},
     * {@code WARN}, {@code ERROR}). The change is applied immediately but is not
     * persisted across restarts.
     *
     * @return {@code 200} with {@code {logger, level}} on success;
     *         {@code 400} when the body is missing or the level can't be parsed
     */
    @PUT
    @Path("{logger}")
    @Consumes(APPLICATION_JSON)
    @Produces(APPLICATION_JSON)
    public Response set(@PathParam("logger") String loggerName, LevelRequest body) {
        final AuthResult auth = requireMasterAdmin();
        if (body == null || body.level() == null || body.level().isBlank()) {
            return Response.status(BAD_REQUEST)
                    .entity(Map.of("error", "level is required"))
                    .build();
        }
        final LoggerName name = LoggerName.parse(loggerName);
        final String caller = auth.user().getUsername();
        final String applied;
        try {
            applied = serviceFor(auth).set(name, body.level(), caller);
        }
        catch (IllegalArgumentException e) {
            return Response.status(BAD_REQUEST)
                    .entity(Map.of("error", "unknown level: " + body.level()))
                    .build();
        }
        LOG.infof("Log level for '%s' set to %s by %s", name.display(), applied, caller);
        return Response.ok(Map.of("logger", name.display(), "level", applied)).build();
    }

    /**
     * Reset a logger to its startup baseline (or clear it if no baseline was
     * recorded — e.g. for a logger created post-startup via the synthetic-row UI).
     */
    @DELETE
    @Path("{logger}")
    public Response reset(@PathParam("logger") String loggerName) {
        final AuthResult auth = requireMasterAdmin();
        final LoggerName name = LoggerName.parse(loggerName);
        final String caller = auth.user().getUsername();
        serviceFor(auth).reset(name, caller);
        final String baseline = baselines.get(name);
        if (baseline == null) {
            LOG.infof("Log level for '%s' reset by %s", name.display(), caller);
        } else {
            LOG.infof("Log level for '%s' reset to baseline %s by %s", name.display(), baseline, caller);
        }
        return Response.noContent().build();
    }

    private LogLevelService serviceFor(AuthResult auth) {
        final ClusterBroadcaster broadcaster = new KeycloakClusterBroadcaster(session);
        final AuditPublisher audit = new KeycloakAuditPublisher(session, auth);
        return new LogLevelService(loggers, baselines, broadcaster, audit);
    }

    /**
     * Authenticate the bearer token and verify the caller is a master-realm
     * admin. The policy is JVM-global, so the contract is that only
     * master-realm admins can change it.
     *
     * @throws NotAuthorizedException if no valid bearer token is present
     * @throws ForbiddenException     if not on the master realm or the caller lacks the {@code admin} role
     */
    private AuthResult requireMasterAdmin() {
        final AuthResult auth = new BearerTokenAuthenticator(session).authenticate();
        if (auth == null) {
            throw new NotAuthorizedException("Bearer");
        }
        final RealmModel callRealm = session.getContext().getRealm();
        if (callRealm == null || !MASTER_REALM.equals(callRealm.getName())) {
            throw new ForbiddenException("Logging endpoint must be called on the master realm");
        }
        final RoleModel adminRole = callRealm.getRole(MASTER_ADMIN_ROLE);
        if (adminRole == null || !auth.user().hasRole(adminRole)) {
            throw new ForbiddenException("Master realm 'admin' role is required");
        }
        return auth;
    }

    /** Request body for {@link #set}. */
    public record LevelRequest(String level) {
    }
}
