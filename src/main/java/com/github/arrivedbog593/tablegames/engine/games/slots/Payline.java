package com.github.arrivedbog593.tablegames.engine.games.slots;

/**
 * A path across the three reels that pays when its symbols line up.
 * <p>
 * Declared in the order they switch on: playing one line plays the middle,
 * two adds the top, three the bottom, four and five the diagonals. Rows count
 * from the top of the window, zero to two.
 */
public enum Payline {

    MIDDLE(1, 1, 1),
    TOP(0, 0, 0),
    BOTTOM(2, 2, 2),
    /** Top left down to bottom right. */
    DIAGONAL_DOWN(0, 1, 2),
    /** Bottom left up to top right. */
    DIAGONAL_UP(2, 1, 0);

    public static final int MAX = values().length;

    private final int[] rows;

    Payline(int... rows) {
        this.rows = rows;
    }

    /** Which row this line crosses on the given reel. */
    public int rowOn(int reel) {
        return rows[reel];
    }

    /** The first {@code count} lines, the ones a stake of that many lines plays. */
    public static Payline[] firstLines(int count) {
        if (count < 1 || count > MAX) {
            throw new IllegalArgumentException("Between 1 and " + MAX + " lines: " + count);
        }
        Payline[] lines = new Payline[count];
        System.arraycopy(values(), 0, lines, 0, count);
        return lines;
    }
}
