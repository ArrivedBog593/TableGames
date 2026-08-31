package com.github.arrivedbog593.tablegames.engine.table;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * Who is sitting at a table, who is only watching, and who has declared
 * themselves finished betting.
 * <p>
 * Opening a table always makes someone a spectator. Sitting down is a
 * separate, deliberate act, and the seat count is capped — which is what
 * stops a ninth player from ever reaching a game session built for eight. The
 * cap used to be enforced nowhere and discovered by an exception inside a
 * block entity tick.
 * <p>
 * Seats are a list rather than a set because order is turn order. A poker
 * table where the button moves has to know who sits after whom, and a set
 * cannot answer that.
 * <p>
 * Nothing here persists. Rounds are not saved across a restart, so neither is
 * the seating: everybody comes back standing, which is the only state that
 * cannot be wrong.
 * <p>
 * Pure Java, no Minecraft. Not thread-safe; server thread only.
 */
public final class TableOccupancy {

    private final int maxSeats;

    private final List<UUID> seats = new ArrayList<>();
    private final Set<UUID> spectators = new LinkedHashSet<>();
    private final Set<UUID> ready = new LinkedHashSet<>();

    /** Consecutive rounds a seated player has sat out. */
    private final Map<UUID, Integer> idleRounds = new HashMap<>();

    /** Ticks left before an absent player loses their seat. */
    private final Map<UUID, Integer> absence = new HashMap<>();

    /** Seats an eviction wanted to free while the round was locked. */
    private final Set<UUID> evictionPending = new LinkedHashSet<>();

    /** How long a seat survives with nobody looking at it. */
    public static final int DEFAULT_ABSENCE_SECONDS = 90;

    private final int absenceTicks;

    public TableOccupancy(int maxSeats) {
        this(maxSeats, DEFAULT_ABSENCE_SECONDS);
    }

    public TableOccupancy(int maxSeats, int absenceSeconds) {
        if (absenceSeconds < 1) {
            throw new IllegalArgumentException("Absence must be positive: " + absenceSeconds);
        }
        this.absenceTicks = absenceSeconds * BettingWindow.TICKS_PER_SECOND;
        this.maxSeats = validate(maxSeats);
    }

    private static int validate(int maxSeats) {
        if (maxSeats < 1) {
            throw new IllegalArgumentException("A table needs at least one seat: " + maxSeats);
        }
        return maxSeats;
    }

    // --- Arriving and leaving --------------------------------------------------

    /**
     * Somebody opened the table. They watch until they choose otherwise.
     *
     * @return true if they were not already here
     */
    public boolean arrive(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        // Reopening the table always cancels an eviction in progress, seat or
        // no seat. Somebody looking at the screen is not absent.
        absence.remove(playerId);
        evictionPending.remove(playerId);
        if (seats.contains(playerId)) {
            return false;
        }
        return spectators.add(playerId);
    }

    /**
     * Somebody closed the table, disconnected, or was removed.
     * <p>
     * Unconditional, including during a lockout. A disconnect is not a button
     * anybody can be stopped from pressing, so it cannot be refused — it has
     * to be survivable instead. It is: wagers are settled against real
     * balances at spin time and do not need their owner present, so a player
     * who vanishes mid-round is still settled exactly as if they had stayed.
     * Refunding them here is the tempting mistake, and the one that would let
     * a poker player escape a losing hand by pulling their cable.
     *
     * @return true if they were here at all
     */
    public boolean depart(UUID playerId) {
        boolean wasHere = spectators.remove(playerId) | seats.remove(playerId);
        ready.remove(playerId);
        idleRounds.remove(playerId);
        absence.remove(playerId);
        evictionPending.remove(playerId);
        return wasHere;
    }

    /**
     * A seated player stopped looking at the table — screen closed, client
     * crashed, connection dropped. The three are the same event from here.
     * <p>
     * The seat is kept and a clock starts. Losing it to a misclick would be
     * worse than holding it for a minute and a half, and there is no way to
     * tell an accident from an exit anyway.
     * <p>
     * They are marked ready as they go. Their wagers stay on the layout and
     * settle without them, so they have nothing left to decide; leaving the
     * flag unset would let one empty chair hold the ready button hostage for
     * everybody else, which is the problem the clock exists to solve, not to
     * create.
     *
     * @return true if there was a seated player to mark absent
     */
    public boolean markAbsent(UUID playerId) {
        if (!seats.contains(playerId)) {
            spectators.remove(playerId);
            return false;
        }
        spectators.remove(playerId);
        absence.put(playerId, absenceTicks);
        ready.add(playerId);
        return true;
    }

    public boolean isAbsent(UUID playerId) {
        return absence.containsKey(playerId) || evictionPending.contains(playerId);
    }

    /** Seconds before this player loses their seat, zero if they are present. */
    public int absenceSecondsLeft(UUID playerId) {
        Integer ticks = absence.get(playerId);
        if (ticks == null) {
            return 0;
        }
        return (ticks + BettingWindow.TICKS_PER_SECOND - 1) / BettingWindow.TICKS_PER_SECOND;
    }

    /**
     * Advances every absence clock by one tick and frees the seats that ran
     * out.
     * <p>
     * An eviction that comes due while betting is closed is held rather than
     * applied. Standing a player up during the lockout is exactly the move
     * the lockout forbids — it would pull a live stake out of a round about
     * to resolve — so the seat is freed at the next phase that permits seat
     * changes instead.
     *
     * @param phase the round's phase
     * @return the players who just lost their seats, empty on most ticks
     */
    public List<UUID> tickAbsences(RoundPhase phase) {
        Objects.requireNonNull(phase, "phase");
        List<UUID> evicted = new ArrayList<>();

        if (!absence.isEmpty()) {
            var iterator = absence.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                int left = entry.getValue() - 1;
                if (left > 0) {
                    entry.setValue(left);
                } else {
                    iterator.remove();
                    evictionPending.add(entry.getKey());
                }
            }
        }

        if (!evictionPending.isEmpty() && phase.allowsStanding()) {
            for (UUID playerId : List.copyOf(evictionPending)) {
                evictionPending.remove(playerId);
                if (seats.remove(playerId)) {
                    ready.remove(playerId);
                    idleRounds.remove(playerId);
                    evicted.add(playerId);
                }
            }
        }
        return evicted;
    }

    // --- Sitting and standing ---------------------------------------------------

    /**
     * Takes a seat.
     *
     * @param phase the round's phase; seats are frozen once betting closes
     */
    public SeatChange sit(UUID playerId, RoundPhase phase) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(phase, "phase");
        if (seats.contains(playerId)) {
            return SeatChange.ALREADY_SEATED;
        }
        if (!spectators.contains(playerId)) {
            return SeatChange.NOT_AT_TABLE;
        }
        if (!phase.allowsSitting()) {
            return SeatChange.ROUND_LOCKED;
        }
        if (seats.size() >= maxSeats) {
            return SeatChange.TABLE_FULL;
        }
        spectators.remove(playerId);
        seats.add(playerId);
        idleRounds.put(playerId, 0);
        return SeatChange.SEATED;
    }

    /**
     * Gives up a seat and goes back to watching.
     * <p>
     * The caller is responsible for whatever the seat was holding — in
     * roulette, returning the wagers; in a card game, folding the hand. This
     * class only tracks who is where.
     */
    public SeatChange stand(UUID playerId, RoundPhase phase) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(phase, "phase");
        if (!seats.contains(playerId)) {
            return SeatChange.NOT_SEATED;
        }
        if (!phase.allowsStanding()) {
            return SeatChange.ROUND_LOCKED;
        }
        seats.remove(playerId);
        ready.remove(playerId);
        idleRounds.remove(playerId);
        absence.remove(playerId);
        evictionPending.remove(playerId);
        spectators.add(playerId);
        return SeatChange.STOOD;
    }

    // --- Declaring ready ----------------------------------------------------------

    /**
     * Marks a seated player finished, or changes their mind back.
     *
     * @return true if their state changed
     */
    public boolean setReady(UUID playerId, boolean isReady) {
        if (!seats.contains(playerId)) {
            return false;
        }
        return isReady ? ready.add(playerId) : ready.remove(playerId);
    }

    /**
     * Whether every seated player has declared themselves ready, which is
     * what lets the round skip the rest of its clock.
     * <p>
     * Every seated player, not only those who wagered. Counting only the
     * bettors would let one player place a token wager, declare ready, and
     * find themselves unanimous — cutting the window short for a table that
     * had not finished thinking. An empty table is never unanimous.
     */
    public boolean allSeatedReady() {
        return !seats.isEmpty() && ready.size() == seats.size();
    }

    /** Clears every ready flag, for the start of a new round. */
    public void clearReady() {
        ready.clear();
    }

    public boolean isReady(UUID playerId) {
        return ready.contains(playerId);
    }

    // --- Rounds sat out --------------------------------------------------------------

    /**
     * Records that a round finished, given who took part in it.
     * <p>
     * Nothing acts on the count yet. It exists now so that dropping idle
     * players later is a rule change rather than a state change: a table that
     * has not been counting cannot start enforcing anything about the past.
     */
    public void noteRoundEnded(Collection<UUID> participants) {
        Objects.requireNonNull(participants, "participants");
        for (UUID seated : seats) {
            if (participants.contains(seated)) {
                idleRounds.put(seated, 0);
            } else {
                idleRounds.merge(seated, 1, Integer::sum);
            }
        }
    }

    /** Consecutive rounds this player has sat out. */
    public int idleRoundsOf(UUID playerId) {
        return idleRounds.getOrDefault(playerId, 0);
    }

    // --- Reading it ------------------------------------------------------------------

    public int maxSeats() {
        return maxSeats;
    }

    /** Seated players, in turn order. */
    public List<UUID> seats() {
        return List.copyOf(seats);
    }

    /** Players watching without a seat. */
    public Set<UUID> spectators() {
        return Set.copyOf(spectators);
    }

    /** Everyone with the table open, seated or not. */
    public Set<UUID> everyone() {
        Set<UUID> all = new LinkedHashSet<>(seats);
        all.addAll(spectators);
        return all;
    }

    public boolean isSeated(UUID playerId) {
        return seats.contains(playerId);
    }

    public boolean isPresent(UUID playerId) {
        return seats.contains(playerId) || spectators.contains(playerId);
    }

    public OptionalInt seatIndexOf(UUID playerId) {
        int index = seats.indexOf(playerId);
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    public Optional<UUID> seatAt(int index) {
        return index < 0 || index >= seats.size()
                ? Optional.empty()
                : Optional.of(seats.get(index));
    }

    public int seatedCount() {
        return seats.size();
    }

    public int spectatorCount() {
        return spectators.size();
    }

    public boolean hasFreeSeat() {
        return seats.size() < maxSeats;
    }

    public boolean isEmpty() {
        return seats.isEmpty() && spectators.isEmpty();
    }

    /** Drops everything. For when the table's game changes, or it is broken. */
    public void clear() {
        seats.clear();
        spectators.clear();
        ready.clear();
        idleRounds.clear();
        absence.clear();
        evictionPending.clear();
    }
}