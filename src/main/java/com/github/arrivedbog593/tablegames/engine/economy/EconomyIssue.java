package com.github.arrivedbog593.tablegames.engine.economy;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Something wrong with an economy configuration.
 * <p>
 * Messages are plain English rather than translation keys: these go to the
 * server console for whoever is editing the config, not to players in-game.
 *
 * @param severity how bad it is
 * @param itemId   the item at fault for disabling it if configured to
 * @param message  what is wrong and how to fix it
 */
public record EconomyIssue(Severity severity, String itemId, String message) {

    public enum Severity {
        /** Exploitable. Credits can be created from nothing. */
        ERROR,
        /** Suspicious but not exploitable on its own. */
        WARNING
    }

    public EconomyIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(message, "message");
    }

    public static EconomyIssue error(String itemId, String message) {
        return new EconomyIssue(Severity.ERROR, itemId, message);
    }

    public static EconomyIssue warning(String itemId, String message) {
        return new EconomyIssue(Severity.WARNING, itemId, message);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public @NotNull String toString() {
        return severity + ": " + message;
    }
}