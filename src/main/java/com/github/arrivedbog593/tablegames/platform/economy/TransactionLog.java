package com.github.arrivedbog593.tablegames.platform.economy;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionRecord;
import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Append-only record of every credit movement, rotated per run.
 * <p>
 * Two jobs. Day to day it is an audit trail: when a player says their balance
 * is wrong, the alternative to a log is guessing. After a crash it is a
 * recovery source, because balances only reach disk when the world saves
 * while this is written line by line.
 * <p>
 * The active file covers the current run only. On startup the previous run's
 * file is read for recovery, then gzipped into a subdirectory, so the live
 * file never grows without bound no matter how long the server has been up.
 * <p>
 * Everything lives under {@code <world>/logs/}, alongside the balances it
 * describes, rather than under the game folder. See
 * {@link #logDirectoryOf(MinecraftServer)} for why that matters.
 * <p>
 * Server thread only.
 */
public final class TransactionLog {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String ACTIVE_NAME = "tablegames-transactions.log";
    private static final String ARCHIVE_DIRECTORY = "tablegames";
    private static final String ARCHIVE_PREFIX = "transactions-";

    /** Roll over mid-run past this size, so one busy night cannot balloon the file. */
    private static final long MAX_ACTIVE_BYTES = 10L * 1024 * 1024;

    /** Archives older than this are deleted. Old audit trails help nobody. */
    private static final int RETENTION_DAYS = 30;

    private final Path activeFile;
    private final Path archiveDirectory;

    private BufferedWriter writer;
    private long sequence;
    private long bytesWritten;

    private TransactionLog(Path activeFile, Path archiveDirectory, long startingSequence) {
        this.activeFile = activeFile;
        this.archiveDirectory = archiveDirectory;
        this.sequence = startingSequence;
    }

    // --- Lifecycle -----------------------------------------------------------

    /**
     * The log directory for this world.
     * <p>
     * Inside the world folder, not the game folder. Balances are level data
     * and therefore per world; a log outside the world would be shared by
     * every world the same installation opens, and startup recovery would
     * replay one world's pending movements into another's balances. It also
     * means the log travels with a world backup, which is exactly when
     * somebody wants to read it.
     */
    private static Path logDirectoryOf(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("logs");
    }

    /** Where the previous run left its log, whether or not it exists. */
    public static Path activeFileOf(MinecraftServer server) {
        return logDirectoryOf(server).resolve(ACTIVE_NAME);
    }

    /**
     * Opens a fresh log for this run.
     * <p>
     * Call only after recovery has read the previous file, since this
     * archives whatever was there.
     *
     * @param startingSequence the highest sequence already known, so numbers
     *                         keep climbing across restarts
     */
    public static TransactionLog open(MinecraftServer server, long startingSequence) {
        Path logs = logDirectoryOf(server);
        Path archives = logs.resolve(ARCHIVE_DIRECTORY);
        TransactionLog log = new TransactionLog(logs.resolve(ACTIVE_NAME), archives, startingSequence);
        try {
            Files.createDirectories(archives);
            log.archiveActive();
            log.openWriter();
            log.pruneArchives();
        } catch (IOException e) {
            LOGGER.error("Could not open the transaction log; movements will not be recorded", e);
        }
        return log;
    }

    /** Reads back every parsable line of a log file, ignoring damaged ones. */
    public static List<TransactionRecord> read(Path file) {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        List<TransactionRecord> records = new ArrayList<>();
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> TransactionRecord.parse(line).ifPresent(records::add));
        } catch (IOException e) {
            LOGGER.error("Could not read the transaction log at {}", file, e);
        }
        return records;
    }

    /** Flushes and closes. Called when the server stops. */
    public void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
            writer.close();
        } catch (IOException e) {
            LOGGER.error("Failed to close the transaction log", e);
        } finally {
            writer = null;
        }
    }

    // --- Recording ------------------------------------------------------------

    public long currentSequence() {
        return sequence;
    }

    /**
     * Records one movement and returns its sequence number.
     * <p>
     * Flushed immediately. A line that is still in a buffer when the process
     * dies is a line that cannot help recover from that death.
     */
    public long record(TransactionType type, UUID owner, long delta, long balance, String detail) {
        long assigned = ++sequence;
        TransactionRecord entry = new TransactionRecord(
                assigned, Instant.now().toString(), type, owner, delta, balance, detail);
        write(entry.toLine());
        return assigned;
    }

    /** Records a movement of the house bank rather than a player's balance. */
    public long recordHouse(TransactionType type, long delta, long balance, String detail) {
        return record(type, TransactionRecord.HOUSE, delta, balance, detail);
    }

    private void write(String line) {
        if (writer == null) {
            return;
        }
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
            bytesWritten += line.length() + 1;
            if (bytesWritten >= MAX_ACTIVE_BYTES) {
                rollOver();
            }
        } catch (IOException e) {
            // A failed log write must never abort a transaction the player has
            // already committed to; losing an audit line beats losing credits.
            LOGGER.error("Failed to write a transaction line", e);
        }
    }

    // --- Rotation --------------------------------------------------------------

    private void rollOver() throws IOException {
        close();
        archiveActive();
        openWriter();
        pruneArchives();
    }

    private void openWriter() throws IOException {
        writer = Files.newBufferedWriter(activeFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        bytesWritten = Files.exists(activeFile) ? Files.size(activeFile) : 0;
    }

    /** Compresses the active file into the archive folder and clears it. */
    private void archiveActive() throws IOException {
        if (!Files.exists(activeFile) || Files.size(activeFile) == 0) {
            Files.deleteIfExists(activeFile);
            return;
        }
        Path target = nextArchiveName();
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            Files.copy(activeFile, out);
        }
        Files.delete(activeFile);
        LOGGER.info("Archived the previous transaction log to {}", target.getFileName());
    }

    /**
     * Picks the next free archive name, numbering runs within a day the same
     * way vanilla numbers its own log archives.
     */
    private Path nextArchiveName() {
        String date = LocalDate.now(ZoneId.systemDefault()).toString();
        for (int run = 1; run < 1000; run++) {
            Path candidate = archiveDirectory.resolve(
                    ARCHIVE_PREFIX + date + "-" + run + ".log.gz");
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return archiveDirectory.resolve(
                ARCHIVE_PREFIX + date + "-" + System.currentTimeMillis() + ".log.gz");
    }

    /** Deletes archives past the retention window. */
    private void pruneArchives() {
        Instant cutoff = Instant.now().minusSeconds(RETENTION_DAYS * 86_400L);
        try (Stream<Path> files = Files.list(archiveDirectory)) {
            List<Path> stale = files
                    .filter(path -> path.getFileName().toString().startsWith(ARCHIVE_PREFIX))
                    .filter(path -> isOlderThan(path, cutoff))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
            for (Path path : stale) {
                Files.deleteIfExists(path);
            }
            if (!stale.isEmpty()) {
                LOGGER.info("Pruned {} transaction log archive(s) older than {} days",
                        stale.size(), RETENTION_DAYS);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not prune transaction log archives", e);
        }
    }

    private static boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}