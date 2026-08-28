package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetTypeTest {

    private static Pocket pocket(int number) {
        return RouletteWheel.EUROPEAN.pockets().stream()
                .filter(p -> p.number() == number && !p.doubleZero())
                .findFirst()
                .orElseThrow();
    }

    @Test
    void redAndBlackFollowTheStandardLayout() {
        assertTrue(BetType.RED.wins(pocket(1), null));
        assertTrue(BetType.RED.wins(pocket(36), null));
        assertTrue(BetType.BLACK.wins(pocket(2), null));
        assertTrue(BetType.BLACK.wins(pocket(35), null));
        assertFalse(BetType.RED.wins(pocket(2), null));
    }

    @Test
    void zeroLosesEveryOutsideBet() {
        Pocket zero = pocket(0);
        for (BetType type : BetType.values()) {
            if (type.isOutsideBet()) {
                assertFalse(type.wins(zero, null), type + " must lose to zero");
            }
        }
    }

    @Test
    void doubleZeroLosesEveryOutsideBet() {
        Pocket doubleZero = Pocket.doubleZeroPocket();
        for (BetType type : BetType.values()) {
            if (type.isOutsideBet()) {
                assertFalse(type.wins(doubleZero, null), type + " must lose to double zero");
            }
        }
    }

    @Test
    void zeroIsNotEvenForWageringPurposes() {
        // Mathematically even, but a losing bet. This is the classic leak.
        assertFalse(BetType.EVEN.wins(pocket(0), null));
        assertFalse(BetType.EVEN.wins(Pocket.doubleZeroPocket(), null));
    }

    @Test
    void straightUpMatchesOnlyItsOwnPocket() {
        assertTrue(BetType.STRAIGHT_UP.wins(pocket(17), pocket(17)));
        assertFalse(BetType.STRAIGHT_UP.wins(pocket(18), pocket(17)));
    }

    @Test
    void straightUpDistinguishesZeroFromDoubleZero() {
        assertTrue(BetType.STRAIGHT_UP.wins(Pocket.zero(), Pocket.zero()));
        assertFalse(BetType.STRAIGHT_UP.wins(Pocket.zero(), Pocket.doubleZeroPocket()));
        assertFalse(BetType.STRAIGHT_UP.wins(Pocket.doubleZeroPocket(), Pocket.zero()));
    }

    @Test
    void dozensCoverTwelveNumbersEach() {
        assertEquals(12, countWins(BetType.DOZEN_FIRST));
        assertEquals(12, countWins(BetType.DOZEN_SECOND));
        assertEquals(12, countWins(BetType.DOZEN_THIRD));
    }

    @Test
    void columnsCoverTwelveNumbersEach() {
        assertEquals(12, countWins(BetType.COLUMN_FIRST));
        assertEquals(12, countWins(BetType.COLUMN_SECOND));
        assertEquals(12, countWins(BetType.COLUMN_THIRD));
        assertTrue(BetType.COLUMN_FIRST.wins(pocket(34), null));
        assertTrue(BetType.COLUMN_THIRD.wins(pocket(36), null));
    }

    @Test
    void evenMoneyBetsCoverEighteenNumbersEach() {
        for (BetType type : new BetType[]{
                BetType.RED, BetType.BLACK, BetType.ODD,
                BetType.EVEN, BetType.LOW, BetType.HIGH}) {
            assertEquals(18, countWins(type), type + " coverage");
        }
    }

    private static long countWins(BetType type) {
        return RouletteWheel.EUROPEAN.pockets().stream()
                .filter(p -> type.wins(p, null))
                .count();
    }
}