package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.RecoveryPlan;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionRecord;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Restores credit balances lost to a crash.
 * <p>
 * Balances live in memory and reach disk only when the world saves, every
 * five minutes by default. A crash loses everything since then. The
 * transaction log, flushed line by line, still has it.
 * <p>
 * Runs once at server start, before the log is rotated, and is a no-op on a
 * clean shutdown because the saved sequence already covers every line.
 * <p>
 * Reports what it found at every branch, including the boring ones. Silence
 * from a recovery routine is indistinguishable from a recovery routine that
 * never ran, and telling those apart afterwards is impossible.
 */
public final class TransactionRecovery {

    private static final Logger LOGGER = LogUtils.getLogger();

    private TransactionRecovery() {
    }

    /**
     * @param restored  accounts whose balance changed
     * @param applied   log lines replayed
     * @param discarded log lines dropped because they moved items
     * @param scanned   log lines that were not yet on disk
     */
    public record Report(int restored, int applied, int discarded, int scanned) {
        public boolean didAnything() {
            return restored > 0;
        }
    }

    /**
     * Reads the previous run's log and restores what it safely can.
     * <p>
     * Must run before {@link TransactionLog#open}, which archives the file
     * this reads.
     */
    public static Report run(MinecraftServer server, CreditStorage storage) {
        Path previous = TransactionLog.activeFileOf(server);
        long saved = storage.lastSequence();

        if (!Files.exists(previous)) {
            LOGGER.info("[Economy] No transaction log from a previous run at {}. "
                    + "Saved sequence is {}.", previous, saved);
            return new Report(0, 0, 0, 0);
        }

        List<TransactionRecord> records = TransactionLog.read(previous);
        LOGGER.info("[Economy] Read {} transaction line(s) from {}. "
                        + "Balances on disk cover up to sequence {}.",
                records.size(), previous.getFileName(), saved);

        RecoveryPlan plan = RecoveryPlan.build(saved, records);

        if (plan.consideredCount() == 0) {
            LOGGER.info("[Economy] Nothing to recover: the last shutdown saved everything.");
            storage.setLastSequence(plan.highestSequence());
            return new Report(0, 0, 0, 0);
        }

        LOGGER.warn("[Economy] The previous run ended without saving. {} movement(s) "
                        + "were logged but never written to disk.",
                plan.consideredCount());

        if (plan.discardedCount() > 0) {
            LOGGER.warn("[Economy] Dropping {} of them: they exchanged items, which came "
                    + "back with the world rollback. Replaying the credits too would "
                    + "have duplicated value.", plan.discardedCount());
        }

        for (Map.Entry<UUID, Long> entry : plan.deltas().entrySet()) {
            long before = storage.balanceOf(entry.getKey());
            long after = Math.max(0, before + entry.getValue());
            storage.setBalance(entry.getKey(), after);
            LOGGER.info("[Economy] Restored {}: {} -> {} ({}{})",
                    entry.getKey(), before, after,
                    entry.getValue() >= 0 ? "+" : "", entry.getValue());
        }

        if (plan.houseDelta() != 0) {
            long before = storage.houseBalance();
            long after = Math.max(0, before + plan.houseDelta());
            storage.setHouseBalance(after);
            LOGGER.info("[Economy] Restored the house bank: {} -> {}", before, after);
        }

        storage.setLastSequence(plan.highestSequence());
        LOGGER.info("[Economy] Recovery complete. {} account(s) restored from {} "
                        + "replayed movement(s).",
                plan.affectedCount(), plan.appliedCount());

        return new Report(plan.affectedCount(), plan.appliedCount(),
                plan.discardedCount(), plan.consideredCount());
    }
}