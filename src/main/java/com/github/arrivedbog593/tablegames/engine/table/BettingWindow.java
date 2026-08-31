package com.github.arrivedbog593.tablegames.engine.table;

/**
 * The clock a table runs between the first wager and the spin.
 * <p>
 * Thirty seconds in total, of which the last five are a lockout: the croupier
 * saying "no more bets" while the wheel is already turning. Splitting it that
 * way means a player cannot place a wager into a round that is about to
 * resolve, and — more importantly — cannot pull one back out of it.
 * <p>
 * Pure tick arithmetic with no Minecraft anywhere, so the awkward parts (what
 * happens on the tick the lockout begins, what a unanimous ready does to a
 * clock already inside the lockout) are unit tested rather than reasoned
 * about once and hoped for.
 * <p>
 * Not thread-safe. Driven from the server tick.
 */
public final class BettingWindow {

    /** Vanilla ticks per second. */
    public static final int TICKS_PER_SECOND = 20;

    /** Seconds of open betting, before the lockout. */
    public static final int DEFAULT_OPEN_SECONDS = 25;

    /** Seconds of "no more bets" at the end of the window. */
    public static final int DEFAULT_LOCK_SECONDS = 5;

    /** How long the winning pocket stays on screen. */
    public static final int DEFAULT_RESULT_SECONDS = 6;

    /**
     * What a tick did, so the caller knows whether anything is worth telling
     * the clients about.
     */
    public enum Event {
        /** Nothing anybody needs to see. */
        NONE,
        /** A whole second passed; the countdown on the screen changed. */
        SECOND_ELAPSED,
        /** Betting just closed. */
        LOCKED,
        /** The window ran out. Run the game. */
        SPIN,
        /** The result stopped being shown; the table is idle again. */
        RESULT_CLEARED
    }

    private final int openTicks;
    private final int lockTicks;
    private final int resultTicks;

    /** Ticks left before the spin, lockout included. Zero when not running. */
    private int remaining;

    /** Ticks left showing the result. Zero when not showing one. */
    private int showing;

    public BettingWindow() {
        this(DEFAULT_OPEN_SECONDS, DEFAULT_LOCK_SECONDS, DEFAULT_RESULT_SECONDS);
    }

    public BettingWindow(int openSeconds, int lockSeconds, int resultSeconds) {
        if (openSeconds <= 0 || lockSeconds < 0 || resultSeconds < 0) {
            throw new IllegalArgumentException("Window seconds must be positive: "
                    + openSeconds + "/" + lockSeconds + "/" + resultSeconds);
        }
        this.openTicks = openSeconds * TICKS_PER_SECOND;
        this.lockTicks = lockSeconds * TICKS_PER_SECOND;
        this.resultTicks = resultSeconds * TICKS_PER_SECOND;
    }

    // --- Reading it ----------------------------------------------------------

    public RoundPhase phase() {
        if (showing > 0) {
            return RoundPhase.RESULT;
        }
        if (remaining <= 0) {
            return RoundPhase.IDLE;
        }
        return remaining <= lockTicks ? RoundPhase.LOCKED : RoundPhase.OPEN;
    }

    /**
     * Seconds left before the wheel turns, rounded up so the display never
     * shows zero while the round is still running.
     */
    public int secondsRemaining() {
        return remaining <= 0 ? 0 : (remaining + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    /** Seconds of the result still on screen. */
    public int resultSecondsRemaining() {
        return showing <= 0 ? 0 : (showing + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND;
    }

    public boolean isRunning() {
        return remaining > 0;
    }

    // --- Driving it -----------------------------------------------------------

    /**
     * Starts the clock if it is not already running.
     *
     * @return true if this call started it
     */
    public boolean start() {
        if (remaining > 0 || showing > 0) {
            return false;
        }
        remaining = openTicks + lockTicks;
        return true;
    }

    /**
     * Skips straight to the spin, for when every seated player has declared
     * themselves ready.
     * <p>
     * Deliberately ignores the lockout. The lockout exists to force a final
     * moment where nobody can change their mind; a table where everyone has
     * already said they are finished has produced that moment itself, and
     * making them sit through five more seconds only makes the ready button
     * less worth pressing.
     *
     * @return true if there was a running clock to cut short
     */
    public boolean callNow() {
        if (remaining <= 0) {
            return false;
        }
        remaining = 1;
        return true;
    }

    /** Puts the result on screen and stops the betting clock. */
    public void showResult() {
        remaining = 0;
        showing = resultTicks;
    }

    /** Drops everything: no clock, no result. */
    public void reset() {
        remaining = 0;
        showing = 0;
    }

    /**
     * Advances one tick.
     * <p>
     * The lockout event fires on the tick the phase changes, not on the one
     * after, so a client is told betting has closed before it can send another
     * wager into the gap.
     */
    public Event tick() {
        if (showing > 0) {
            showing--;
            return showing == 0 ? Event.RESULT_CLEARED : secondBoundary(showing);
        }
        if (remaining <= 0) {
            return Event.NONE;
        }

        boolean wasOpen = remaining > lockTicks;
        remaining--;
        if (remaining == 0) {
            return Event.SPIN;
        }
        if (wasOpen && remaining <= lockTicks) {
            return Event.LOCKED;
        }
        return secondBoundary(remaining);
    }

    private static Event secondBoundary(int ticks) {
        return ticks % TICKS_PER_SECOND == 0 ? Event.SECOND_ELAPSED : Event.NONE;
    }
}
