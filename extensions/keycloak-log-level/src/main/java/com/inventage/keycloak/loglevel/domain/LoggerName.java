package com.inventage.keycloak.loglevel.domain;

import static java.util.Objects.requireNonNull;

/**
 * Value object for a JBoss / java.util.logging logger name. The unnamed root
 * logger is represented by an empty value, exposed as the {@value #ROOT_ALIAS}
 * alias on the wire — the LogManager identifies it by the empty string.
 *
 * <p>Pure domain type — no Keycloak, JBoss, or framework imports.
 */
public record LoggerName(String value) {

    public static final LoggerName ROOT = new LoggerName("");
    public static final String ROOT_ALIAS = "ROOT";

    public LoggerName {
        requireNonNull(value, "value");
    }

    /**
     * Build a {@code LoggerName} from operator input. The {@value #ROOT_ALIAS}
     * sentinel (case-insensitive) maps to the unnamed root logger.
     */
    public static LoggerName parse(String input) {
        requireNonNull(input, "input");
        return ROOT_ALIAS.equalsIgnoreCase(input) ? ROOT : new LoggerName(input);
    }

    public boolean isRoot() {
        return value.isEmpty();
    }

    /** Display name to surface back to operators — empty root becomes {@value #ROOT_ALIAS}. */
    public String display() {
        return isRoot() ? ROOT_ALIAS : value;
    }
}
