package com.github.arrivedbog593.tablegames.engine.table;

/**
 * Where a table is in its round.
 * <p>
 * An explicit enum rather than something inferred from two tick counters.
 * The counters worked while there were two states; with a lockout in the
 * middle, "is betting open" stops being a question a reader can answer by
 * looking at a subtraction, and every game added afterward inherits the
 * confusion.
 * <p>
 * The three permissions below — betting, seat changes, and whether a round is
 * running at all — are what the platform layer actually asks about, so they
 * live here instead of being re-derived at each call site.
 */
public enum RoundPhase {

    /**
     * Nothing is happening. The first wager placed starts the clock.
     * <p>
     * Distinct from {@link #OPEN} because a table waiting all night should
     * not be counting anything down.
     */
    IDLE(true, true, true),

    /** The clock is running and wagers are still accepted. */
    OPEN(true, true, true),

    /**
     * The last seconds before the wheel turns. "No more bets."
     * <p>
     * Wagers are frozen: none placed, none withdrawn, and nobody standing up
     * out of the round. That last part is the point — allowing an exit here
     * would let a player pull a losing stake back out at the last instant,
     * and blackjack and poker would inherit the same hole in a far more
     * exploitable form.
     * <p>
     * Sitting down is still allowed. Taking a seat commits nothing, so there
     * is nothing to escape from; the newcomer simply cannot bet until the
     * next round, which is exactly what a croupier waving somebody to a chair
     * after calling "no more bets" amounts to.
     */
    LOCKED(false, true, false),

    /** The result is on screen. The table resets when it clears. */
    RESULT(false, true, true);

    private final boolean acceptsBets;
    private final boolean allowsSitting;
    private final boolean allowsStanding;

    RoundPhase(boolean acceptsBets, boolean allowsSitting, boolean allowsStanding) {
        this.acceptsBets = acceptsBets;
        this.allowsSitting = allowsSitting;
        this.allowsStanding = allowsStanding;
    }

    /** Whether a wager may be placed or withdrawn right now. */
    public boolean acceptsBets() {
        return acceptsBets;
    }

    /**
     * Whether a player may take a seat right now.
     * <p>
     * Separate from {@link #allowsStanding()} because the two are different
     * risk. Sitting commits nothing; standing up mid-lockout would carry
     * a live stake out of a round about to resolve. Treating them as one
     * permission meant the lockout turned away newcomers for no reason.
     */
    public boolean allowsSitting() {
        return allowsSitting;
    }

    /** Whether a player may give up a seat right now. */
    public boolean allowsStanding() {
        return allowsStanding;
    }

    /** Whether the clock is counting toward a spin. */
    public boolean isCountingDown() {
        return this == OPEN || this == LOCKED;
    }

    public String translationKey() {
        return "tablegames.phase." + name().toLowerCase(java.util.Locale.ROOT);
    }
}