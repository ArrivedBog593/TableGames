package com.github.arrivedbog593.tablegames.engine.table;

import java.util.Optional;

/**
 * How much a player may bring to a table, and add to it later.
 * <p>
 * A buy-in is a reservation, not a transfer. The credits stay in the
 * player's balance and only stop being spendable anywhere else: every wager
 * still moves at settlement, exactly as before, so a crash or a broken table
 * loses nothing and a restart simply forgets the reservation. What the stack
 * adds is a ceiling of the player's own choosing — sit down with five
 * thousand out of twenty-five, and five thousand is all this table can take
 * from you, "all in" included.
 * <p>
 * The minimum applies to sitting down. Topping up has none, because a player
 * who is already in has already cleared it; it is only held to the maximum,
 * counted against the whole stack and not the top-up alone.
 *
 * @param minimum the least a player may sit down with, at least one
 * @param maximum the most a stack may hold, or {@link #UNLIMITED} for as much
 *                as the player can cover
 */
public record BuyIn(long minimum, long maximum) {

    /** A maximum of zero: no ceiling of the table's own. */
    public static final long UNLIMITED = 0L;

    /** Why a buy-in or top-up was turned down. */
    public enum Problem {
        NOT_POSITIVE("tablegames.buyin.not_positive"),
        BELOW_MINIMUM("tablegames.buyin.below_minimum"),
        ABOVE_MAXIMUM("tablegames.buyin.above_maximum"),
        INSUFFICIENT("tablegames.buyin.insufficient");

        private final String translationKey;

        Problem(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    public BuyIn {
        if (minimum < 1) {
            throw new IllegalArgumentException("Buy-in minimum below one: " + minimum);
        }
        if (maximum != UNLIMITED && maximum < minimum) {
            throw new IllegalArgumentException(
                    "Buy-in maximum " + maximum + " below its minimum " + minimum);
        }
    }

    public boolean isCapped() {
        return maximum != UNLIMITED;
    }

    /** The most a stack may hold, as a plain number. */
    public long ceiling() {
        return isCapped() ? maximum : Long.MAX_VALUE;
    }

    /**
     * Whether this much may be added to a stack.
     * <p>
     * The minimum is a price of admission and nothing else: it says what it
     * takes to sit down, not what it takes to keep playing. Somebody already
     * in the seat may top up by any amount they like, including less than
     * the minimum — they paid it when they arrived.
     * <p>
     * Whether this is an arrival is asked of the caller rather than guessed
     * from an empty stack. A player who has lost everything is still sitting
     * there, and being told to buy in again from scratch to put one more
     * credit in is exactly the rule this used to get wrong.
     *
     * @param amount    what the player asked to bring
     * @param stack     what they already have at this table
     * @param available what they could still reserve: their balance less
     *                  everything already spoken for, this table included
     * @param joining   whether this is the buy-in that seats them
     */
    public Optional<Problem> problemWith(long amount, long stack, long available,
                                         boolean joining) {
        if (amount <= 0) {
            return Optional.of(Problem.NOT_POSITIVE);
        }
        if (joining && amount < minimum) {
            return Optional.of(Problem.BELOW_MINIMUM);
        }
        if (amount > ceiling() - stack) {
            return Optional.of(Problem.ABOVE_MAXIMUM);
        }
        if (amount > available) {
            return Optional.of(Problem.INSUFFICIENT);
        }
        return Optional.empty();
    }

    /**
     * The most that may be added right now, what a "maximum" button fills in.
     * Zero when nothing may: the stack is full, or nothing is available.
     */
    public long largestAddition(long stack, long available) {
        return Math.max(0L, Math.min(ceiling() - stack, available));
    }
}
