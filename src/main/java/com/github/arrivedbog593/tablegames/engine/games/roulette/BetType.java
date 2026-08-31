package com.github.arrivedbog593.tablegames.engine.games.roulette;

import java.util.Locale;

/**
 * The kinds of wager a roulette table accepts.
 * <p>
 * Payout ratios are "to one": a winning {@link #RED} returns the stake plus
 * one times the stake, a winning {@link #STRAIGHT_UP} returns the stake plus
 * thirty-five times the stake.
 * <p>
 * Every ratio here is calculated as if the wheel had 36 pockets. Real wheels
 * have 37 or 38, and that gap is the house edge. Never "fix" a ratio to
 * account for the zeros: doing so would double-count the edge and quietly
 * gut the players.
 */
public enum BetType {

    /** A single number, including 0 or 00. Requires a target pocket. */
    STRAIGHT_UP(35, true),

    RED(1, false),
    BLACK(1, false),
    ODD(1, false),
    EVEN(1, false),

    /** 1 to 18, traditionally called "low" or manqué. */
    LOW(1, false),
    /** 19 to 36, traditionally called "high" or passé. */
    HIGH(1, false),

    DOZEN_FIRST(2, false),
    DOZEN_SECOND(2, false),
    DOZEN_THIRD(2, false),

    /** Numbers 1, 4, 7 ... 34. */
    COLUMN_FIRST(2, false),
    /** Numbers 2, 5, 8 ... 35. */
    COLUMN_SECOND(2, false),
    /** Numbers 3, 6, 9 ... 36. */
    COLUMN_THIRD(2, false);

    private final int payoutRatio;
    private final boolean requiresTarget;

    BetType(int payoutRatio, boolean requiresTarget) {
        this.payoutRatio = payoutRatio;
        this.requiresTarget = requiresTarget;
    }

    /** Profit per credit staked on a win. The stake is returned on top. */
    public int payoutRatio() {
        return payoutRatio;
    }

    /** Whether the bet names a specific pocket. */
    public boolean requiresTarget() {
        return requiresTarget;
    }

    /** True when this bet covers more than one number. */
    public boolean isOutsideBet() {
        return !requiresTarget;
    }

    public String translationKey() {
        return "tablegames.roulette.bet." + name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether this bet wins against the given result.
     * <p>
     * Note that the zeros lose every outside bet, including EVEN. Zero is
     * mathematically even but not for wagering purposes, and that exception
     * is where naive implementations leak money.
     *
     * @param result the pocket the ball landed in
     * @param target the pocket named by a straight-up bet, ignored otherwise
     */
    public boolean wins(Pocket result, Pocket target) {
        if (this == STRAIGHT_UP) {
            return result.equals(target);
        }
        if (result.isZero()) {
            return false;
        }
        int n = result.number();
        return switch (this) {
            case RED -> result.color() == PocketColor.RED;
            case BLACK -> result.color() == PocketColor.BLACK;
            case ODD -> n % 2 == 1;
            case EVEN -> n % 2 == 0;
            case LOW -> n <= 18;
            case HIGH -> n >= 19;
            case DOZEN_FIRST -> n <= 12;
            case DOZEN_SECOND -> n >= 13 && n <= 24;
            case DOZEN_THIRD -> n >= 25;
            case COLUMN_FIRST -> n % 3 == 1;
            case COLUMN_SECOND -> n % 3 == 2;
            case COLUMN_THIRD -> n % 3 == 0;
            case STRAIGHT_UP -> throw new IllegalStateException("handled above");
        };
    }

    /**
     * Whether this is an inside bet — one placed on the numbered grid rather
     * than on the surrounding boxes.
     * <p>
     * The split a real table draws when it posts two maximums: inside bets
     * pay steeply and are capped low, outside bets pay little and are capped
     * far higher, because the same stake exposes the house to wildly
     * different amounts. Derived from the payout rather than listed by hand,
     * so a split or a corner added later classifies itself.
     */
    public boolean isInside() {
        return payoutRatio >= 5;
    }
}
