package com.github.arrivedbog593.tablegames.engine.economy;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One line of the transaction log.
 * <p>
 * Tab separated so it can be grepped, sorted or opened in a spreadsheet with
 * no tooling, and parsed back without a JSON dependency.
 * <p>
 * Each line carries the resulting balance rather than only the delta. That
 * makes replay idempotent: applying the same line twice lands on the same
 * number, so a recovery that runs twice cannot compound.
 *
 * @param sequence  monotonic counter, the anchor for crash recovery
 * @param timestamp ISO-8601 instant, for humans reading the file
 * @param type      what the movement was for
 * @param owner     whose balance moved; {@link #HOUSE} for the house bank
 * @param delta     signed change
 * @param balance   the balance after this movement
 * @param detail    free text: the item, the table, the operator
 */
public record TransactionRecord(long sequence, String timestamp, TransactionType type,
                                UUID owner, long delta, long balance, String detail) {

    /** Stand-in owner for movements of the house bank. */
    public static final UUID HOUSE = new UUID(0L, 0L);

    private static final char SEPARATOR = '\t';
    private static final int FIELD_COUNT = 7;

    public TransactionRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(owner, "owner");
        timestamp = timestamp == null ? "" : timestamp;
        detail = detail == null ? "" : detail;
    }

    public boolean isHouse() {
        return HOUSE.equals(owner);
    }

    /** Serialises to a single log line, without a trailing newline. */
    public String toLine() {
        return String.join(String.valueOf(SEPARATOR),
                Long.toString(sequence),
                timestamp,
                type.code(),
                owner.toString(),
                Long.toString(delta),
                Long.toString(balance),
                sanitise(detail));
    }

    /**
     * Parses a log line.
     * <p>
     * Returns empty rather than throwing on anything malformed. A log can be
     * truncated mid-write by the very crash it is meant to help recover from,
     * so a broken last line is expected, not exceptional.
     */
    public static Optional<TransactionRecord> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] parts = line.split(String.valueOf(SEPARATOR), -1);
        if (parts.length != FIELD_COUNT) {
            return Optional.empty();
        }
        try {
            long sequence = Long.parseLong(parts[0]);
            TransactionType type = TransactionType.fromCode(parts[2]);
            if (type == null) {
                return Optional.empty();
            }
            return Optional.of(new TransactionRecord(
                    sequence,
                    parts[1],
                    type,
                    UUID.fromString(parts[3]),
                    Long.parseLong(parts[4]),
                    Long.parseLong(parts[5]),
                    parts[6]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String sanitise(String text) {
        return text.replace(SEPARATOR, ' ').replace('\n', ' ').replace('\r', ' ');
    }
}