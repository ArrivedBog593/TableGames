package com.github.arrivedbog593.tablegames.engine.session;

import java.util.Objects;
import java.util.UUID;

/**
 * A player sitting at a table.
 * <p>
 * A seat deliberately does NOT hold cards. Card shapes differ per game — Uno
 * uses colors and actions rather than ranks and suits — so each
 * {@link GameSession} keeps its own hand storage. Putting a card list here
 * would force every game into one card model.
 * <p>
 * {@code credits} is the chip stack brought to the table, not the player's
 * persisted balance. The platform layer moves credits in when the player
 * sits and out when they leave; the engine only ever does arithmetic on
 * plain longs and never touches persistence.
 */
public final class Seat {

    private final int index;
    private final UUID playerId;
    private final boolean bot;

    private long credits;
    private long currentBet;
    private SeatStatus status = SeatStatus.SITTING_OUT;

    public Seat(int index, UUID playerId, long credits, boolean bot) {
        if (index < 0) {
            throw new IllegalArgumentException("Negative seat index: " + index);
        }
        if (credits < 0) {
            throw new IllegalArgumentException("Negative credits: " + credits);
        }
        this.index = index;
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.credits = credits;
        this.bot = bot;
    }

    public static Seat forPlayer(int index, UUID playerId, long credits) {
        return new Seat(index, playerId, credits, false);
    }

    public static Seat forBot(int index, UUID botId, long credits) {
        return new Seat(index, botId, credits, true);
    }

    public int index() {
        return index;
    }

    public UUID playerId() {
        return playerId;
    }

    public boolean isBot() {
        return bot;
    }

    public long credits() {
        return credits;
    }

    public long currentBet() {
        return currentBet;
    }

    public SeatStatus status() {
        return status;
    }

    public void setStatus(SeatStatus status) {
        this.status = Objects.requireNonNull(status, "status");
    }

    /**
     * Moves credits from the stack into the current bet.
     *
     * @return the amount actually wagered, capped at the remaining stack, so
     *         an all-in for less than the asked amount still works
     */
    public long wager(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative wager: " + amount);
        }
        long actual = Math.min(amount, credits);
        credits -= actual;
        currentBet += actual;
        return actual;
    }

    /** Adds winnings to the stack. */
    public void award(long amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("Negative award: " + amount);
        }
        credits += amount;
    }

    /** Clears the wager for the next betting round and returns what it was. */
    public long collectBet() {
        long collected = currentBet;
        currentBet = 0;
        return collected;
    }

    /** Stack exhausted: the player is all-in. */
    public boolean isAllIn() {
        return credits == 0 && currentBet > 0;
    }

    @Override
    public String toString() {
        return "Seat[" + index + ", " + playerId + ", " + credits + "cr, " + status + "]";
    }
}