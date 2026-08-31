package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What the house currently stands to lose across every open table at once.
 * <p>
 * {@link HouseBankroll#maximumExposure()} answers "how much may one payout
 * take from the bankroll", and every table was asking it independently. Eight
 * roulette tables, therefore, each believed they had the whole five percent to
 * themselves, and a busy casino could commit eight times what the bankroll was
 * ever meant to risk. The individual limits were all respected, and the total
 * was still wrong.
 * <p>
 * This is the missing total. A table reports what it would owe in its worst
 * case, and a new wager is only taken if the sum across every table still fits
 * inside the bankroll's exposure. What one table has committed is therefore
 * unavailable to the others, which is the whole point.
 * <p>
 * Nothing here persists. Rounds are not saved across a restart, so a
 * commitment cannot outlive the round that made it, and a registry rebuilt
 * empty is always correct.
 * <p>
 * Pure Java, no Minecraft. Not thread-safe; server thread only.
 */
public final class HouseExposure {

    /** Worst-case liability per table, keyed by whatever identifies one. */
    private final Map<String, Long> committed = new HashMap<>();

    /** The total the house is currently exposed to everywhere. */
    public long total() {
        long sum = 0;
        for (long value : committed.values()) {
            sum += value;
        }
        return sum;
    }

    /** What one table has committed. */
    public long committedBy(String tableKey) {
        return committed.getOrDefault(tableKey, 0L);
    }

    /**
     * Whether a table could move to the new worst case.
     * <p>
     * Its existing commitment is replaced rather than added to, because a
     * table reports the total liability of its whole round each time, not the
     * increment. Adding would double-count everything already on the layout.
     *
     * @param tableKey        which table is asking
     * @param worstCase       what it would owe if the round went against the
     *                        house entirely
     * @param maximumExposure the bankroll's ceiling, from {@link HouseBankroll}
     */
    public boolean fits(String tableKey, long worstCase, long maximumExposure) {
        Objects.requireNonNull(tableKey, "tableKey");
        if (worstCase < 0) {
            throw new IllegalArgumentException("Negative exposure: " + worstCase);
        }
        return total() - committedBy(tableKey) + worstCase <= maximumExposure;
    }

    /** Records a table's worst case, replacing whatever it had before. */
    public void commit(String tableKey, long worstCase) {
        Objects.requireNonNull(tableKey, "tableKey");
        if (worstCase < 0) {
            throw new IllegalArgumentException("Negative exposure: " + worstCase);
        }
        if (worstCase == 0) {
            committed.remove(tableKey);
        } else {
            committed.put(tableKey, worstCase);
        }
    }

    /** Frees a table's commitment, for when its round ends, or it is broken. */
    public void release(String tableKey) {
        committed.remove(tableKey);
    }

    /** How many tables currently have anything riding on them. */
    public int tableCount() {
        return committed.size();
    }

    /** Drops everything. For a server shutting down or starting fresh. */
    public void clear() {
        committed.clear();
    }
}
