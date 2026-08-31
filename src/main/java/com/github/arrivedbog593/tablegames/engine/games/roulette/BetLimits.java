package com.github.arrivedbog593.tablegames.engine.games.roulette;

/**
 * The minimums and maximums one table chooses to impose.
 * <p>
 * Separate from the limit derived from the bankroll, and always narrower than
 * it: the derived limit answers "what can the house afford to pay", this one
 * answers "what kind of table is this". They are different questions, and
 * tying them together would mean a server could not run a beginners' table and
 * a high-limit one out of the same bankroll — raising the bankroll to survive
 * a bad run would raise every table's ceiling along with it.
 * <p>
 * Two maximums rather than one, the way a real wheel posts them. Inside bets
 * pay up to thirty-five to one and outside bets pay one to one, so the same
 * stake exposes the house to thirty-five times as much; a single flat cap
 * either strangles the outside bets or fails to restrain the inside ones.
 * <p>
 * {@link #UNLIMITED} means the table imposes nothing and the bankroll alone
 * decides. That is the default, because a table should not quietly cap what
 * the house can afford unless somebody said so.
 */
public record BetLimits(long insideMinimum, long insideMaximum,
                        long outsideMinimum, long outsideMaximum) {

    /** No ceiling of its own; whatever the bankroll allows. */
    public static final long UNLIMITED = 0;

    /** The smallest chip a table will take unless told otherwise. */
    public static final long DEFAULT_MINIMUM = 10;

    /** A table that restricts nothing beyond the house's own limit. */
    public static final BetLimits DEFAULT =
            new BetLimits(DEFAULT_MINIMUM, UNLIMITED, DEFAULT_MINIMUM, UNLIMITED);

    public BetLimits {
        if (insideMinimum < 1 || outsideMinimum < 1) {
            throw new IllegalArgumentException(
                    "Minimums must be positive: " + insideMinimum + "/" + outsideMinimum);
        }
        if (insideMaximum != UNLIMITED && insideMaximum < insideMinimum) {
            throw new IllegalArgumentException(
                    "Inside maximum below its minimum: " + insideMaximum);
        }
        if (outsideMaximum != UNLIMITED && outsideMaximum < outsideMinimum) {
            throw new IllegalArgumentException(
                    "Outside maximum below its minimum: " + outsideMaximum);
        }
    }

    /** The smallest wager this table takes on a bet of the given type. */
    public long minimumFor(BetType type) {
        return type.isInside() ? insideMinimum : outsideMinimum;
    }

    /**
     * The largest wager this table takes on a bet of the given type, or
     * {@link Long#MAX_VALUE} when it imposes none.
     * <p>
     * Returning the maximum long rather than a flag lets callers write a
     * plain {@code Math.min} against the derived limit, which is exactly what
     * "the stricter of the two wins" should look like.
     */
    public long maximumFor(BetType type) {
        long limit = type.isInside() ? insideMaximum : outsideMaximum;
        return limit == UNLIMITED ? Long.MAX_VALUE : limit;
    }

    public boolean hasInsideMaximum() {
        return insideMaximum != UNLIMITED;
    }

    public boolean hasOutsideMaximum() {
        return outsideMaximum != UNLIMITED;
    }

    /** Whether this table adds anything at all to the house's own limits. */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    public BetLimits withInside(long minimum, long maximum) {
        return new BetLimits(minimum, maximum, outsideMinimum, outsideMaximum);
    }

    public BetLimits withOutside(long minimum, long maximum) {
        return new BetLimits(insideMinimum, insideMaximum, minimum, maximum);
    }
}
