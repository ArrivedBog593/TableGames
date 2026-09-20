package com.github.arrivedbog593.tablegames.engine.games.slots;

import java.util.List;
import java.util.Objects;

/**
 * One reel: its symbols in the order they pass the window.
 * <p>
 * Every stop is equally likely. That is not the only way to build a reel, but
 * it is the one that keeps every row honest: with weighted stops, the rows
 * above and below the one the weights were tuned for would land on different
 * odds, and the lines through them would pay a different return than the
 * middle one while looking identical.
 *
 * @param stops the strip, wrapping around from last to first
 */
public record Reel(List<SlotSymbol> stops) {

    /** A window shows three rows: the stop, and one either side of it. */
    public static final int ROWS = 3;

    public Reel {
        stops = List.copyOf(Objects.requireNonNull(stops, "stops"));
        if (stops.size() < ROWS) {
            throw new IllegalArgumentException("A reel needs at least " + ROWS + " stops");
        }
    }

    public int size() {
        return stops.size();
    }

    /**
     * What shows in the window when the reel stops here: the row above, the
     * stop itself in the middle, and the row below.
     */
    public SlotSymbol at(int stop, int row) {
        return stops.get(Math.floorMod(stop + row - 1, stops.size()));
    }

    /** How many stops carry this symbol. */
    public int count(SlotSymbol symbol) {
        int count = 0;
        for (SlotSymbol stop : stops) {
            if (stop == symbol) {
                count++;
            }
        }
        return count;
    }
}
