package com.github.arrivedbog593.tablegames.engine.games.slots;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlotMachineTest {

    private static final SlotsGame GAME = SlotsGame.standard();

    /** Every level pays what its label says, whichever number of lines is played. */
    @Test
    void eachPaybackReturnsWhatItIsLabelledWith() {
        for (SlotsGame.Payback level : SlotsGame.Payback.values()) {
            SlotMachine machine = new SlotMachine(GAME.reels(), level.paytable());
            double labelled = Integer.parseInt(level.percent()) / 100.0;
            for (int lines = 1; lines <= Payline.MAX; lines++) {
                double actual = machine.returnToPlayer(lines);
                assertTrue(Math.abs(actual - labelled) < 0.003,
                        level + " on " + lines + " lines returns " + actual);
            }
        }
    }

    @Test
    void theHouseAlwaysKeepsAnEdge() {
        for (SlotsGame.Payback level : SlotsGame.Payback.values()) {
            SlotMachine machine = new SlotMachine(GAME.reels(), level.paytable());
            assertTrue(machine.returnToPlayer(Payline.MAX) < 1.0, level + " pays out more than it takes");
        }
    }

    @Test
    void everyReelCarriesTheSameSymbols() {
        for (Reel reel : GAME.reels()) {
            assertEquals(32, reel.size());
            assertEquals(8, reel.count(SlotSymbol.COAL));
            assertEquals(2, reel.count(SlotSymbol.NETHERITE));
            assertEquals(2, reel.count(SlotSymbol.REPLAY));
        }
    }

    @Test
    void theWindowWrapsAroundTheStrip() {
        Reel reel = new Reel(List.of(SlotSymbol.COAL, SlotSymbol.IRON, SlotSymbol.GOLD,
                SlotSymbol.DIAMOND));
        assertEquals(SlotSymbol.DIAMOND, reel.at(0, 0), "above the first stop is the last");
        assertEquals(SlotSymbol.COAL, reel.at(0, 1));
        assertEquals(SlotSymbol.COAL, reel.at(3, 2), "below the last stop is the first");
    }

    @Test
    void linesSwitchOnMiddleFirstThenTopBottomThenDiagonals() {
        assertEquals(List.of(Payline.MIDDLE), List.of(Payline.firstLines(1)));
        assertEquals(List.of(Payline.MIDDLE, Payline.TOP, Payline.BOTTOM),
                List.of(Payline.firstLines(3)));
        assertEquals(5, Payline.firstLines(5).length);
        assertThrows(IllegalArgumentException.class, () -> Payline.firstLines(0));
        assertThrows(IllegalArgumentException.class, () -> Payline.firstLines(6));
    }

    @Test
    void threeOfAKindPaysBySymbol() {
        Paytable table = SlotsGame.Payback.P95.paytable();
        assertEquals(670, table.evaluate(SlotSymbol.NETHERITE, SlotSymbol.NETHERITE,
                SlotSymbol.NETHERITE).multiple());
        assertEquals(7, table.evaluate(SlotSymbol.COAL, SlotSymbol.COAL, SlotSymbol.COAL)
                .multiple(), "three coal pays the three-coal price, not the two-coal one");
    }

    @Test
    void twoCoalFromTheLeftPaysALittle() {
        Paytable table = SlotsGame.Payback.P95.paytable();
        assertEquals(2, table.evaluate(SlotSymbol.COAL, SlotSymbol.COAL, SlotSymbol.GOLD)
                .multiple());
        assertFalse(table.evaluate(SlotSymbol.GOLD, SlotSymbol.COAL, SlotSymbol.COAL).pays(),
                "coal counts from the left only");
    }

    @Test
    void threeReplaysWinASpinAndNoCredits() {
        Paytable.LineWin win = SlotsGame.Payback.P95.paytable()
                .evaluate(SlotSymbol.REPLAY, SlotSymbol.REPLAY, SlotSymbol.REPLAY);
        assertTrue(win.replay());
        assertEquals(0, win.multiple());
    }

    @Test
    void onlyTheLinesPlayedArePaid() {
        // Every row shows iron: each line played wins, and no other.
        SlotMachine machine = uniform(SlotSymbol.IRON);
        for (int lines = 1; lines <= Payline.MAX; lines++) {
            SlotMachine.SpinResult result = machine.resultAt(new int[]{0, 0, 0}, lines);
            assertEquals(lines, result.wins().size());
            assertEquals(lines * 14, result.totalMultiple());
        }
    }

    @Test
    void theLargestPayoutIsCountedNotGuessed() {
        assertEquals(5 * 14, uniform(SlotSymbol.IRON).maxMultiple(5));
        SlotMachine standard = new SlotMachine(GAME.reels(), SlotsGame.Payback.P95.paytable());
        assertTrue(standard.maxMultiple(5) >= 670, "at least one line of netherite");
        assertTrue(standard.maxMultiple(1) <= standard.maxMultiple(5));
    }

    /** A machine whose every stop shows the same symbol. */
    static SlotMachine uniform(SlotSymbol symbol) {
        Reel reel = new Reel(Collections.nCopies(4, symbol));
        return new SlotMachine(List.of(reel, reel, reel), SlotsGame.Payback.P95.paytable());
    }

    @Test
    void aPaytableNeedsEverySymbolPriced() {
        Map<SlotSymbol, Integer> missing = new EnumMap<>(SlotSymbol.class);
        missing.put(SlotSymbol.COAL, 5);
        assertThrows(IllegalArgumentException.class, () -> new Paytable(missing, 2));
    }
}
