package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What each player has riding on rounds that have not been settled yet.
 * <p>
 * Wagers are not debited when they are placed — they move at settlement, which
 * is what makes abandoning a round its own refund. The gap that leaves is that
 * a balance can be spent somewhere else while a wager is still live. A player
 * could bet everything, wait for betting to lock, buy an item at the cashier
 * with the same credits, and watch the spin drop a wager they could no longer
 * cover. They neither won nor lost it: a cancellation, during the one phase
 * that exists to make canceling impossible, paid for with an item they could
 * sell straight back.
 * <p>
 * This is the missing subtraction. A table reports what a player has on its
 * layout, and everything that spends credits asks what is left rather than
 * what the balance says. Two tables cannot promise the same credits either,
 * which was the same hole with the second spender being another table.
 * <p>
 * Commitments are reported as totals and not as increments, for the same
 * reason as {@link HouseExposure}: a table knows what is on its felt, and a
 * table that says so twice must not be counted twice.
 * <p>
 * Nothing here persists. Rounds do not survive a restart, so a commitment
 * cannot either, and a registry rebuilt empty is always correct. The danger is
 * the opposite one — a commitment that outlives its round locks credits away
 * until the server stops — so every path that ends a round has to release, and
 * releasing something never committed is deliberately harmless.
 * <p>
 * Pure Java, no Minecraft. Not thread-safe; server thread only.
 */
public final class PlayerCommitments {

    /** Per player, what each table says they have on it. */
    private final Map<UUID, Map<String, Long>> committed = new HashMap<>();

    /**
     * Records what a player has on one table, replacing that table's figure.
     *
     * @param amount their whole stake there; zero releases it
     */
    public void commit(UUID playerId, String tableKey, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(tableKey, "tableKey");
        if (amount < 0) {
            throw new IllegalArgumentException("Negative commitment: " + amount);
        }
        if (amount == 0) {
            release(playerId, tableKey);
            return;
        }
        committed.computeIfAbsent(playerId, id -> new LinkedHashMap<>()).put(tableKey, amount);
    }

    /** Frees what one player had on one table. */
    public void release(UUID playerId, String tableKey) {
        Map<String, Long> tables = committed.get(playerId);
        if (tables == null) {
            return;
        }
        tables.remove(tableKey);
        if (tables.isEmpty()) {
            committed.remove(playerId);
        }
    }

    /**
     * Frees everything one table was holding, for everybody.
     * <p>
     * What a round calls when it settles, refunds, or is broken up.
     */
    public void release(String tableKey) {
        Objects.requireNonNull(tableKey, "tableKey");
        committed.values().forEach(tables -> tables.remove(tableKey));
        committed.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** What this player has riding on every table at once. */
    public long committedBy(UUID playerId) {
        Map<String, Long> tables = committed.get(playerId);
        if (tables == null) {
            return 0L;
        }
        long sum = 0L;
        for (long amount : tables.values()) {
            sum += amount;
        }
        return sum;
    }

    /** What this player has on one table in particular. */
    public long committedBy(UUID playerId, String tableKey) {
        return committed.getOrDefault(playerId, Map.of()).getOrDefault(tableKey, 0L);
    }

    /**
     * What this player has riding everywhere except one table.
     * <p>
     * What a table needs to judge its own wagers: its own chips are
     * already accounted for by the stake it is about to replace, and counting
     * them again would have a player unable to raise a bet they had already
     * placed.
     */
    public long committedElsewhere(UUID playerId, String tableKey) {
        return committedBy(playerId) - committedBy(playerId, tableKey);
    }

    /**
     * What a player may actually spend, given what they hold.
     * <p>
     * Never negative. A balance can fall below what is committed — an
     * operator can set it, and a losing round can take it — and a spender
     * asking what is left deserves "nothing", not a negative allowance that
     * every caller would have to remember to clamp.
     */
    public long spendable(UUID playerId, long balance) {
        return Math.max(0L, balance - committedBy(playerId));
    }

    /** Whether this much can be spent without touching a live wager. */
    public boolean canSpend(UUID playerId, long balance, long amount) {
        return amount <= spendable(playerId, balance);
    }

    /** How many players have anything committed anywhere. */
    public int playerCount() {
        return committed.size();
    }

    /** Wipes the registry. For a server that is starting or stopping. */
    public void clear() {
        committed.clear();
    }
}
