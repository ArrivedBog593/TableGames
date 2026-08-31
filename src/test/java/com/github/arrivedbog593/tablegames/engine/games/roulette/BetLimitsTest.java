package com.github.arrivedbog593.tablegames.engine.games.roulette;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BetLimitsTest {

    @Test
    void aFreshTableImposesNoCeilingOfItsOwn() {
        // A table should not quietly cap what the house can afford unless
        // somebody said so.
        assertEquals(Long.MAX_VALUE, BetLimits.DEFAULT.maximumFor(BetType.STRAIGHT_UP));
        assertEquals(Long.MAX_VALUE, BetLimits.DEFAULT.maximumFor(BetType.RED));
        assertTrue(BetLimits.DEFAULT.isDefault());
        assertFalse(BetLimits.DEFAULT.hasInsideMaximum());
    }

    @Test
    void insideAndOutsideAreCappedSeparately() {
        // The whole reason for two numbers: the same stake on a straight-up
        // costs the house thirty-five times what it costs on red, so one flat
        // cap either strangles the outside bets or fails to restrain the
        // inside ones.
        BetLimits limits = new BetLimits(10, 5_000, 100, 200_000);
        assertEquals(5_000, limits.maximumFor(BetType.STRAIGHT_UP));
        assertEquals(200_000, limits.maximumFor(BetType.RED));
        assertEquals(10, limits.minimumFor(BetType.STRAIGHT_UP));
        assertEquals(100, limits.minimumFor(BetType.EVEN));
    }

    @Test
    void dozensAndColumnsCountAsOutside() {
        BetLimits limits = new BetLimits(10, 5_000, 10, 200_000);
        assertEquals(200_000, limits.maximumFor(BetType.DOZEN_FIRST));
        assertEquals(200_000, limits.maximumFor(BetType.COLUMN_THIRD));
    }

    @Test
    void straightUpCountsAsInside() {
        assertTrue(BetType.STRAIGHT_UP.isInside());
        assertFalse(BetType.RED.isInside());
        assertFalse(BetType.DOZEN_FIRST.isInside());
    }

    @Test
    void anUnlimitedSideStillHonoursTheOther() {
        BetLimits limits = new BetLimits(10, 5_000, 10, BetLimits.UNLIMITED);
        assertEquals(5_000, limits.maximumFor(BetType.STRAIGHT_UP));
        assertEquals(Long.MAX_VALUE, limits.maximumFor(BetType.RED));
        assertTrue(limits.hasInsideMaximum());
        assertFalse(limits.hasOutsideMaximum());
        assertFalse(limits.isDefault());
    }

    @Test
    void unlimitedReadsAsTheLargestPossibleSoCallersCanJustTakeAMinimum() {
        // Returning a sentinel rather than a flag is what lets the platform
        // write min(derived, table) and have "the stricter wins" fall out.
        long derived = 138_888;
        assertEquals(derived,
                Math.min(derived, BetLimits.DEFAULT.maximumFor(BetType.STRAIGHT_UP)));
        assertEquals(5_000, Math.min(derived,
                new BetLimits(10, 5_000, 10, 0).maximumFor(BetType.STRAIGHT_UP)));
    }

    @Test
    void eachSideCanBeChangedWithoutDisturbingTheOther() {
        BetLimits limits = BetLimits.DEFAULT.withInside(25, 5_000);
        assertEquals(5_000, limits.maximumFor(BetType.STRAIGHT_UP));
        assertEquals(25, limits.minimumFor(BetType.STRAIGHT_UP));
        assertEquals(BetLimits.DEFAULT_MINIMUM, limits.minimumFor(BetType.RED));
        assertEquals(Long.MAX_VALUE, limits.maximumFor(BetType.RED));

        BetLimits both = limits.withOutside(100, 50_000);
        assertEquals(5_000, both.maximumFor(BetType.STRAIGHT_UP));
        assertEquals(50_000, both.maximumFor(BetType.RED));
    }

    @Test
    void aMaximumBelowItsMinimumIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new BetLimits(100, 50, 10, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BetLimits(10, 0, 100, 50));
    }

    @Test
    void aMinimumOfZeroIsRejected() {
        // Otherwise a table would take wagers of nothing.
        assertThrows(IllegalArgumentException.class,
                () -> new BetLimits(0, 5_000, 10, 0));
    }

    @Test
    void anUnlimitedMaximumIsNotBelowItsMinimum() {
        // Zero means "none", not "zero credits", so it must not trip the
        // ordering check.
        BetLimits limits = new BetLimits(10_000, BetLimits.UNLIMITED, 10_000, 0);
        assertEquals(Long.MAX_VALUE, limits.maximumFor(BetType.STRAIGHT_UP));
    }
}
