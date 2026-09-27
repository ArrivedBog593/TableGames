package com.github.arrivedbog593.tablegames.platform.block;

/**
 * What a slot machine announces to the room once its reels are down.
 * <p>
 * Deliberately coarse. The room hears that somebody won, won big, or was
 * handed a free spin; it does not hear how much. The amount is on the
 * glass for anybody who walks over, and a cabinet that sang the figure
 * would be doing the meter's job worse.
 */
public enum SlotFanfare {

    /** Nothing landed, or nothing has been decided yet. */
    NONE,

    /** A win, of the ordinary kind. */
    WIN,

    /**
     * A win worth making a noise about: at least {@link #BIG_WIN_MULTIPLE}
     * times what the pull cost.
     */
    BIG_WIN,

    /** Replays lined up: the machine owes a spin. */
    FREE_SPIN,

    /**
     * The top of the card: three of the best symbol on a line that paid.
     * Its own fanfare, and the one that carries furthest, because it is the
     * one result the whole floor should turn round for.
     */
    JACKPOT;

    /**
     * Where a win starts counting as a big one, as a multiple of the stake.
     * <p>
     * Measured against the whole pull rather than one line, because that is
     * what the player put in. On the default card it makes three gold a big
     * win on one line and three diamonds on five, which is about where a
     * real floor starts calling it one.
     */
    public static final int BIG_WIN_MULTIPLE = 20;

    /**
     * Classifies a spin that has landed.
     * <p>
     * The jackpot outranks everything, a big win outranks a free spin, and a
     * free spin outranks an ordinary win: the spin the machine owes is news
     * the ordinary win is not, and the room only hears one fanfare.
     *
     * @param totalMultiple what every winning line paid, in multiples of a line's stake
     * @param lines         how many lines the pull was played on
     * @param replay        whether replays lined up
     * @param jackpot       whether the best symbol on the card landed three on a paid line
     */
    public static SlotFanfare of(int totalMultiple, int lines, boolean replay, boolean jackpot) {
        if (jackpot) {
            return JACKPOT;
        }
        if (totalMultiple >= (long) BIG_WIN_MULTIPLE * Math.max(1, lines)) {
            return BIG_WIN;
        }
        if (replay) {
            return FREE_SPIN;
        }
        return totalMultiple > 0 ? WIN : NONE;
    }
}
