package com.github.arrivedbog593.tablegames.economy.engine;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What each player has promised and not yet paid: a wager on a round that has
 * not settled, a bid in an auction that has not closed.
 * <p>
 * A promise is not debited when it is made — a wager, for instance, moves at
 * settlement, which is what makes abandoning a round its own refund. Wagers are
 * also where the hole this closes was found. They are not debited when they are placed — they move at settlement, which
 * is what makes abandoning a round its own refund. The gap that leaves is that
 * a balance can be spent somewhere else while a wager is still live. A player
 * could bet everything, wait for betting to lock, buy an item at the cashier
 * with the same credits, and watch the spin drop a wager they could no longer
 * cover. They neither won nor lost it: a cancellation, during the one phase
 * that exists to make canceling impossible, paid for with an item they could
 * sell straight back.
 * <p>
 * This is the missing subtraction. Whatever holds a promise — a table, an
 * auction — reports what each player has promised it, under a key of its own,
 * and everything that spends credits asks what is left rather than what the
 * balance says. Two holders cannot be promised the same credits either, which
 * was the same hole with the second spender being another table.
 * <p>
 * Commitments are reported as totals and not as increments: a holder knows
 * what it is holding, and one that says so twice must not be counted twice.
 * <p>
 * Nothing here persists. What makes promises — rounds, auctions — does not
 * survive a restart, so a promise cannot either, and a registry rebuilt empty
 * is always correct. The danger is the opposite one — a promise that outlives
 * whatever made it locks credits away until the server stops — so every path
 * that ends one has to release, and releasing something never committed is
 * deliberately harmless.
 * <p>
 * Pure Java, no Minecraft. Not thread-safe; server thread only.
 */
public final class PlayerCommitments {

    /** Per player, what each holder says they have promised it. */
    private final Map<UUID, Map<String, Long>> committed = new HashMap<>();

    /**
     * Records what a player has promised one holder, replacing its figure.
     *
     * @param holder whatever holds the promise, as a key of its own: a table,
     *               an auction lot
     * @param amount the whole of it; zero releases it
     */
    public void commit(UUID playerId, String holder, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(holder, "holder");
        if (amount < 0) {
            throw new IllegalArgumentException("Negative commitment: " + amount);
        }
        if (amount == 0) {
            release(playerId, holder);
            return;
        }
        committed.computeIfAbsent(playerId, id -> new LinkedHashMap<>()).put(holder, amount);
    }

    /** Frees what one player had promised one holder. */
    public void release(UUID playerId, String holder) {
        Map<String, Long> holders = committed.get(playerId);
        if (holders == null) {
            return;
        }
        holders.remove(holder);
        if (holders.isEmpty()) {
            committed.remove(playerId);
        }
    }

    /**
     * Frees everything one holder was holding, for everybody.
     * <p>
     * What a round calls when it settles, refunds, or is broken up; what an
     * auction would call when it closes.
     */
    public void release(String holder) {
        Objects.requireNonNull(holder, "holder");
        committed.values().forEach(holders -> holders.remove(holder));
        committed.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    /** What this player has promised, everywhere at once. */
    public long committedBy(UUID playerId) {
        Map<String, Long> holders = committed.get(playerId);
        if (holders == null) {
            return 0L;
        }
        long sum = 0L;
        for (long amount : holders.values()) {
            sum += amount;
        }
        return sum;
    }

    /** What this player has promised one holder in particular. */
    public long committedBy(UUID playerId, String holder) {
        return committed.getOrDefault(playerId, Map.of()).getOrDefault(holder, 0L);
    }

    /**
     * What this player has promised everywhere except one holder.
     * <p>
     * What a table needs to judge its own wagers: its own chips are
     * already accounted for by the stake it is about to replace, and counting
     * them again would have a player unable to raise a bet they had already
     * placed.
     */
    public long committedElsewhere(UUID playerId, String holder) {
        return committedBy(playerId) - committedBy(playerId, holder);
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

    /** Whether this much can be spent without touching anything promised. */
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
