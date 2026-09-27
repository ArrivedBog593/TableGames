package com.github.arrivedbog593.tablegames.engine.games.slots;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Three reels and a paytable: everything that decides what a spin pays.
 * <p>
 * Small enough to know completely. Three reels of a few dozen stops is some
 * tens of thousands of outcomes, so the return is not estimated from a
 * simulation but counted, exactly, by trying every one of them. That is what
 * lets a table post the return it is set to and mean it.
 *
 * @param reels    left to right, exactly three
 * @param paytable what lines pay
 */
public record SlotMachine(List<Reel> reels, Paytable paytable) {

    public static final int REELS = 3;

    public SlotMachine {
        reels = List.copyOf(Objects.requireNonNull(reels, "reels"));
        Objects.requireNonNull(paytable, "paytable");
        if (reels.size() != REELS) {
            throw new IllegalArgumentException("A machine has " + REELS + " reels");
        }
    }

    /** Stops every reel at random and reads the lines being played. */
    public SpinResult spin(RandomGenerator random, int lines) {
        int[] stops = new int[REELS];
        for (int reel = 0; reel < REELS; reel++) {
            stops[reel] = random.nextInt(reels.get(reel).size());
        }
        return resultAt(stops, lines);
    }

    /** What the reels show, and pay, when they stop at these positions. */
    public SpinResult resultAt(int[] stops, int lines) {
        if (stops.length != REELS) {
            throw new IllegalArgumentException("One stop per reel");
        }
        List<List<SlotSymbol>> window = new ArrayList<>(REELS);
        for (int reel = 0; reel < REELS; reel++) {
            List<SlotSymbol> rows = new ArrayList<>(Reel.ROWS);
            for (int row = 0; row < Reel.ROWS; row++) {
                rows.add(reels.get(reel).at(stops[reel], row));
            }
            window.add(List.copyOf(rows));
        }

        Map<Payline, Paytable.LineWin> wins = new EnumMap<>(Payline.class);
        int total = 0;
        boolean replay = false;
        for (Payline line : Payline.firstLines(lines)) {
            Paytable.LineWin win = paytable.evaluate(
                    window.get(0).get(line.rowOn(0)),
                    window.get(1).get(line.rowOn(1)),
                    window.get(2).get(line.rowOn(2)));
            if (win.pays()) {
                wins.put(line, win);
                total += win.multiple();
                replay |= win.replay();
            }
        }
        return new SpinResult(List.of(stops[0], stops[1], stops[2]), List.copyOf(window),
                Map.copyOf(wins), total, replay);
    }

    /**
     * The long-run return on this many lines, as a fraction of what is staked.
     * <p>
     * Counted over every way the reels can stop. A replay is a spin nobody
     * paid for that returns, on average, this same figure again, so the
     * return solves {@code r = base + q * r}: the paid return, grown by the
     * chance of spinning again for free.
     */
    public double returnToPlayer(int lines) {
        Totals totals = countEverything(lines);
        double base = (double) totals.multiples / ((double) totals.outcomes * lines);
        double replayChance = (double) totals.replays / totals.outcomes;
        return base / (1.0 - replayChance);
    }

    /**
     * The most a single spin on this many lines can pay, as a multiple of the
     * stake per line. What the house has to be able to cover before a spin is
     * allowed at all.
     */
    public int maxMultiple(int lines) {
        return countEverything(lines).highest;
    }

    private Totals countEverything(int lines) {
        Totals totals = new Totals();
        int[] stops = new int[REELS];
        for (stops[0] = 0; stops[0] < reels.get(0).size(); stops[0]++) {
            for (stops[1] = 0; stops[1] < reels.get(1).size(); stops[1]++) {
                for (stops[2] = 0; stops[2] < reels.get(2).size(); stops[2]++) {
                    SpinResult result = resultAt(stops, lines);
                    totals.outcomes++;
                    totals.multiples += result.totalMultiple();
                    totals.highest = Math.max(totals.highest, result.totalMultiple());
                    if (result.replay()) {
                        totals.replays++;
                    }
                }
            }
        }
        return totals;
    }

    private static final class Totals {
        long outcomes;
        long multiples;
        long replays;
        int highest;
    }

    /**
     * One spin, as it landed.
     *
     * @param stops         where each reel stopped
     * @param window        what shows, reel by reel, top row first
     * @param wins          every line played that paid, and what
     * @param totalMultiple the sum of every winning line's multiple
     * @param replay        whether any line won a free spin
     */
    public record SpinResult(List<Integer> stops, List<List<SlotSymbol>> window,
                             Map<Payline, Paytable.LineWin> wins, int totalMultiple,
                             boolean replay) {

        /** What a line shows, left to right. */
        public List<SlotSymbol> along(Payline line) {
            List<SlotSymbol> symbols = new ArrayList<>(REELS);
            for (int reel = 0; reel < REELS; reel++) {
                symbols.add(window.get(reel).get(line.rowOn(reel)));
            }
            return symbols;
        }

        /** Whether some line that paid shows three of this symbol. */
        public boolean threeOnAPaidLine(SlotSymbol symbol) {
            for (Payline line : wins.keySet()) {
                if (along(line).stream().allMatch(symbol::equals)) {
                    return true;
                }
            }
            return false;
        }
    }
}
