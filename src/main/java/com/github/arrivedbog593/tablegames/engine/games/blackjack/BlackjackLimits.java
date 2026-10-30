package com.github.arrivedbog593.tablegames.engine.games.blackjack;

/**
 * What a blackjack table takes as an opening wager.
 * <p>
 * One pair of numbers, because there is one place to put chips. A double or a
 * split matches the opening wager whatever it is, so neither is held to
 * these: refusing a double because the doubled wager passed the posted
 * maximum is not a rule any casino has.
 *
 * @param minimum the least a player may open with
 * @param maximum the most, or {@link #UNLIMITED} for no ceiling of the table's own
 */
public record BlackjackLimits(long minimum, long maximum) {

    /** No ceiling beyond what the bankroll allows. */
    public static final long UNLIMITED = 0;

    public BlackjackLimits {
        if (minimum < 1) {
            throw new IllegalArgumentException("A minimum wager is at least one: " + minimum);
        }
        if (maximum != UNLIMITED && maximum < minimum) {
            throw new IllegalArgumentException(
                    "Maximum " + maximum + " is below the minimum " + minimum);
        }
    }

    /** The largest opening wager, with no ceiling read as no limit at all. */
    public long ceiling() {
        return maximum == UNLIMITED ? Long.MAX_VALUE : maximum;
    }

    public boolean allows(long bet) {
        return bet >= minimum && bet <= ceiling();
    }
}
