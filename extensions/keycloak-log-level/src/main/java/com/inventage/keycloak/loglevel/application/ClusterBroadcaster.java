package com.inventage.keycloak.loglevel.application;

import com.inventage.keycloak.loglevel.domain.LoggerName;

/**
 * Port for fanning a level change out to other replicas in the cluster so they
 * apply it to their own JVMs. Implementations should be best-effort: a failed
 * broadcast must not fail the originating request, since the local change has
 * already taken effect.
 */
public interface ClusterBroadcaster {

    void broadcastSet(LoggerName name, String level, String triggeredBy);

    void broadcastReset(LoggerName name, String triggeredBy);
}
