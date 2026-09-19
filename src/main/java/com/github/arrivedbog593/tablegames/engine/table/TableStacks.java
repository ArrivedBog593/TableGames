package com.github.arrivedbog593.tablegames.engine.table;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What each seated player brought to one table, as it rises and falls.
 * <p>
 * Figures only. The credits never leave the player's balance — see
 * {@link BuyIn} — so this is the table's memory of how much of that balance
 * it may take, adjusted by each settled round the same way the balance is.
 * <p>
 * Not persisted, like the seats it belongs to: after a restart everybody is
 * standing and nobody has anything reserved.
 * <p>
 * Pure Java, no Minecraft. Not thread-safe; server thread only.
 */
public final class TableStacks {

    private final Map<UUID, Long> stacks = new LinkedHashMap<>();

    /** Adds to a player's stack, starting one if they had none. */
    public void add(UUID playerId, long amount) {
        Objects.requireNonNull(playerId, "playerId");
        if (amount <= 0) {
            throw new IllegalArgumentException("Non-positive buy-in: " + amount);
        }
        stacks.merge(playerId, amount, Math::addExact);
    }

    /**
     * Moves a stack by what a round paid or took.
     * <p>
     * Never below zero. A stack can only lose what was wagered from it, so
     * reaching below would mean the two layers disagreed; clamping keeps the
     * reservation meaningful instead of turning it into a negative that
     * every reader would have to guard against. A player at zero keeps their
     * seat and their entry here.
     */
    public void settle(UUID playerId, long delta) {
        Long current = stacks.get(playerId);
        if (current == null) {
            return;
        }
        long updated = delta >= 0
                ? saturatedAdd(current, delta)
                : Math.max(0L, current + delta);
        stacks.put(playerId, updated);
    }

    /** Whether this player bought in here. */
    public boolean holds(UUID playerId) {
        return stacks.containsKey(playerId);
    }

    /** What this player has at the table, zero for anybody who has not bought in. */
    public long stackOf(UUID playerId) {
        return stacks.getOrDefault(playerId, 0L);
    }

    /** Forgets a player's stack, for when they stand up or are removed. */
    public void remove(UUID playerId) {
        stacks.remove(playerId);
    }

    /** Everybody with a stack, in the order they bought in. */
    public List<UUID> players() {
        return List.copyOf(stacks.keySet());
    }

    public void clear() {
        stacks.clear();
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < a ? Long.MAX_VALUE : sum;
    }
}
