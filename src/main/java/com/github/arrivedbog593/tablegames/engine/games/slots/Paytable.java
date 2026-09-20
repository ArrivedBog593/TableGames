package com.github.arrivedbog593.tablegames.engine.games.slots;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * What each line pays, as a multiple of the stake on that line.
 * <p>
 * Three of a kind pays by symbol. Two coal from the left pays a little, the
 * way cherries do on a fruit machine, so small wins come often enough to keep
 * a stack alive. Three replays pay nothing and win a free spin instead.
 * <p>
 * The return a machine gives is a property of its paytable and its reels
 * together; see {@link SlotMachine#returnToPlayer}. Different paytables on
 * the same reels are how a machine is set to return more or less, which is
 * how real machines do it too.
 *
 * @param threeOfAKind multiple for three of each paying symbol; every symbol
 *                     but {@link SlotSymbol#REPLAY} must be present
 * @param twoCoal      multiple for coal on the first two reels and not the third
 */
public record Paytable(Map<SlotSymbol, Integer> threeOfAKind, int twoCoal) {

    public Paytable {
        Objects.requireNonNull(threeOfAKind, "threeOfAKind");
        Map<SlotSymbol, Integer> copy = new EnumMap<>(SlotSymbol.class);
        for (SlotSymbol symbol : SlotSymbol.values()) {
            if (symbol == SlotSymbol.REPLAY) {
                continue;
            }
            Integer multiple = threeOfAKind.get(symbol);
            if (multiple == null || multiple < 1) {
                throw new IllegalArgumentException("No positive payout for " + symbol);
            }
            copy.put(symbol, multiple);
        }
        if (twoCoal < 0) {
            throw new IllegalArgumentException("Negative two-coal payout: " + twoCoal);
        }
        threeOfAKind = Map.copyOf(copy);
    }

    /** What one line pays for these three symbols, left to right. */
    public LineWin evaluate(SlotSymbol first, SlotSymbol second, SlotSymbol third) {
        if (first == second && second == third) {
            return first == SlotSymbol.REPLAY
                    ? LineWin.REPLAY
                    : new LineWin(threeOfAKind.get(first), false);
        }
        if (first == SlotSymbol.COAL && second == SlotSymbol.COAL && twoCoal > 0) {
            return new LineWin(twoCoal, false);
        }
        return LineWin.NOTHING;
    }

    /** The multiple for three of a symbol, zero for replay. */
    public int multipleFor(SlotSymbol symbol) {
        return threeOfAKind.getOrDefault(symbol, 0);
    }

    /**
     * What a single line came to.
     *
     * @param multiple of the line's stake, zero for nothing or a replay
     * @param replay   whether the line won a free spin
     */
    public record LineWin(int multiple, boolean replay) {

        public static final LineWin NOTHING = new LineWin(0, false);
        public static final LineWin REPLAY = new LineWin(0, true);

        public boolean pays() {
            return multiple > 0 || replay;
        }
    }
}
