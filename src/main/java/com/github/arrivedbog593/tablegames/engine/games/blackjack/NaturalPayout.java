package com.github.arrivedbog593.tablegames.engine.games.blackjack;

/**
 * What a blackjack — an ace and a ten-value card as the first two — pays.
 * <p>
 * The one rule a casino turns to take more without the felt looking any
 * different. Three to two is the game as it is meant to be played; six to
 * five is what a house offering single-deck blackjack does to pay for it,
 * and it costs the player about one and a half percent.
 * <p>
 * Stored as a position on a table's settings, so constants are appended,
 * never reordered.
 */
public enum NaturalPayout {

    THREE_TO_TWO(3, 2),
    SIX_TO_FIVE(6, 5);

    private final long numerator;
    private final long denominator;

    NaturalPayout(long numerator, long denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    /**
     * What a blackjack on this much wins, on top of the wager coming back.
     * <p>
     * Rounded down: a casino pays a blackjack on an odd chip in the coin it
     * has, and a credit has no smaller coin. Worked out so that it never
     * multiplies the whole wager first — a posted limit sits near the ceiling
     * of what a balance may hold, and three times that is past what a long
     * holds.
     */
    public long winOn(long bet) {
        return bet / denominator * numerator + bet % denominator * numerator / denominator;
    }

    /** The name a setting stores it under. */
    public String optionName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
