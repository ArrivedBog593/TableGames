package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.slots.SlotSymbol;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SlotFanfareTest {

    private static int threeOf(SlotSymbol symbol) {
        return SlotsGame.Payback.P95.paytable().threeOfAKind().get(symbol);
    }

    @Test
    void nothingLandedSaysNothing() {
        assertEquals(SlotFanfare.NONE, SlotFanfare.of(0, 5, false, false));
    }

    @Test
    void anOrdinaryWinIsAWin() {
        assertEquals(SlotFanfare.WIN, SlotFanfare.of(threeOf(SlotSymbol.COAL), 1, false, false));
    }

    /** On one line three gold is already twenty times the pull; on five it takes diamonds. */
    @Test
    void aBigWinIsMeasuredAgainstTheWholePull() {
        assertEquals(SlotFanfare.BIG_WIN, SlotFanfare.of(threeOf(SlotSymbol.GOLD), 1, false, false));
        assertEquals(SlotFanfare.WIN, SlotFanfare.of(threeOf(SlotSymbol.EMERALD), 5, false, false));
        assertEquals(SlotFanfare.BIG_WIN, SlotFanfare.of(threeOf(SlotSymbol.DIAMOND), 5, false, false));
    }

    /** The top of the card beats everything, a free spin and a big win included. */
    @Test
    void theJackpotOutranksEverything() {
        assertEquals(SlotFanfare.JACKPOT, SlotFanfare.of(threeOf(SlotSymbol.NETHERITE), 1, false, true));
        assertEquals(SlotFanfare.JACKPOT, SlotFanfare.of(threeOf(SlotSymbol.NETHERITE), 5, true, true));
    }

    /** The room hears one fanfare, and a spin owed is news an ordinary win is not. */
    @Test
    void aFreeSpinOutranksAnOrdinaryWinButNotABigOne() {
        assertEquals(SlotFanfare.FREE_SPIN, SlotFanfare.of(0, 3, true, false));
        assertEquals(SlotFanfare.FREE_SPIN, SlotFanfare.of(threeOf(SlotSymbol.COAL), 3, true, false));
        assertEquals(SlotFanfare.BIG_WIN, SlotFanfare.of(threeOf(SlotSymbol.NETHERITE), 3, true, false));
    }
}
