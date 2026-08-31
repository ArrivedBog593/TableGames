package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * What to restore after a crash, worked out from the log.
 * <p>
 * Balances live in memory and reach the disk only when the world saves, which by
 * default is every five minutes. A crash therefore loses every credit
 * movement since the last save. The log, written line by line, still has
 * them.
 * <p>
 * Filtering is per transaction, not per player. Each unapplied movement is
 * judged on its own: purely numeric ones are replayed, ones that moved items
 * are dropped. A player who received a grant and also converted some ingots
 * gets the grant back and loses the conversion, which is exactly right —
 * their ingots came back with the world rollback, so the credits for them
 * must not.
 * <p>
 * The result is a set of deltas to add to the saved balances rather than
 * absolute values. Absolutes cannot work once individual entries are
 * dropped: the balance recorded on a later line already includes the
 * conversion being discarded.
 * <p>
 * Idempotent in practice because the sequence number is saved in the same
 * file as the balances. If the process dies again before saving, both revert
 * together and the same lines are replayed onto the same starting point.
 */
public record RecoveryPlan(Map<UUID, Long> deltas,
                           long houseDelta,
                           long highestSequence,
                           int consideredCount,
                           int appliedCount,
                           int discardedCount) {

    public RecoveryPlan {
        deltas = Map.copyOf(Objects.requireNonNull(deltas, "deltas"));
    }

    /** Nothing to restore. */
    public static RecoveryPlan empty(long highestSequence) {
        return new RecoveryPlan(Map.of(), 0, highestSequence, 0, 0, 0);
    }

    public boolean isEmpty() {
        return deltas.isEmpty() && houseDelta == 0;
    }

    /** Accounts that would move the house included. */
    public int affectedCount() {
        return deltas.size() + (houseDelta == 0 ? 0 : 1);
    }

    /**
     * Works out what can be safely restored.
     *
     * @param lastAppliedSequence the highest sequence already on disk; every
     *                            line at or below it is already reflected in
     *                            the saved balances
     * @param records             log lines from the run that crashed, in any
     *                            order
     */
    public static RecoveryPlan build(long lastAppliedSequence, List<TransactionRecord> records) {
        Objects.requireNonNull(records, "records");

        List<TransactionRecord> pending = new ArrayList<>();
        long highest = lastAppliedSequence;
        for (TransactionRecord record : records) {
            highest = Math.max(highest, record.sequence());
            if (record.sequence() > lastAppliedSequence) {
                pending.add(record);
            }
        }
        if (pending.isEmpty()) {
            return RecoveryPlan.empty(highest);
        }

        Map<UUID, Long> deltas = new LinkedHashMap<>();
        long houseDelta = 0;
        int applied = 0;
        int discarded = 0;

        for (TransactionRecord record : pending) {
            if (!record.type().isRecoverable()) {
                discarded++;
                continue;
            }
            applied++;
            if (record.isHouse()) {
                houseDelta += record.delta();
            } else {
                deltas.merge(record.owner(), record.delta(), Long::sum);
            }
        }

        // A player whose only movements canceled out needs no restoring.
        deltas.entrySet().removeIf(entry -> entry.getValue() == 0);

        return new RecoveryPlan(deltas, houseDelta, highest,
                pending.size(), applied, discarded);
    }
}