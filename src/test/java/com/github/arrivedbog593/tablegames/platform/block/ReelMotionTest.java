package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReelMotionTest {

    /** What the server holds a spin for: forty ticks. */
    private static final long SPIN_MILLIS = 2000;

    private static ReelMotion.At at(int reel, long sinceStart) {
        long stopped = sinceStart <= SPIN_MILLIS ? -1 : sinceStart - SPIN_MILLIS;
        return ReelMotion.of(reel, sinceStart, stopped);
    }

    /** Symbols per millisecond, by finite difference. */
    private static double speed(int reel, long sinceStart) {
        return at(reel, sinceStart + 1).symbols() - at(reel, sinceStart).symbols();
    }

    /** A drum that turned backwards for one frame would be the first thing anybody noticed. */
    @Test
    void aReelNeverTurnsBackwards() {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            double last = -1;
            for (long t = 0; t <= SPIN_MILLIS + ReelMotion.LANDING_MILLIS + 200; t++) {
                double now = at(reel, t).symbols();
                assertTrue(now >= last - 1e-9, "reel " + reel + " went back at " + t + " ms");
                last = now;
            }
        }
    }

    /**
     * Each reel stops on a whole symbol, with the result's top row in the top
     * row of the window: what dropped in is what stays.
     */
    @Test
    void everyReelComesToRestExactlyOnItsResult() {
        long end = SPIN_MILLIS + ReelMotion.LANDING_MILLIS;
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            ReelMotion.At rest = at(reel, end);
            assertTrue(rest.resting(), "reel " + reel + " still moving when the landing is over");
            assertEquals(Math.floor(rest.symbols()), rest.symbols(), 1e-9,
                    "reel " + reel + " stopped between two symbols");
            assertEquals(-(int) rest.symbols(), rest.resultAt(),
                    "reel " + reel + " rests somewhere other than its result");
        }
    }

    /**
     * The whole point of constant deceleration: the slowdown starts at the
     * speed the drum was turning, not half again faster, which is what the
     * first version of this did and what made the stop read as a lurch.
     */
    @Test
    void slowingDownStartsAtTheSpeedTheReelWasTurning() {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            long begins = SPIN_MILLIS + ReelMotion.STOP_STAGGER_MILLIS * reel;
            double before = speed(reel, begins - 2);
            double after = speed(reel, begins + 2);
            assertEquals(1.0, after / before, 0.05,
                    "reel " + reel + " jumps from " + before + " to " + after);
        }
    }

    /** It comes down to nothing, rather than stopping from speed. */
    @Test
    void aReelIsBarelyMovingJustBeforeItStops() {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            long t = SPIN_MILLIS;
            while (!at(reel, t).resting()) {
                t++;
            }
            double cruise = speed(reel, SPIN_MILLIS - 10);
            assertTrue(speed(reel, t - 5) < cruise * 0.05,
                    "reel " + reel + " still at " + speed(reel, t - 5) + " five ms from rest");
        }
    }

    /** Left to right, and none of them before the server has said the spin is over. */
    @Test
    void theReelsLandInOrderAndOnlyAfterTheSpin() {
        long previous = 0;
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            assertFalse(at(reel, SPIN_MILLIS).resting());
            long t = SPIN_MILLIS;
            while (!at(reel, t).resting()) {
                t++;
            }
            assertTrue(t > previous, "reel " + reel + " landed before the one to its left");
            previous = t;
        }
    }

    /**
     * At full speed every reel gets its whole slowdown. The cut for slow reels
     * must never bite here, and the slowest reel sits exactly on the edge of
     * it, so this is what catches a constant tuned a little too far.
     */
    @Test
    void aReelAtFullSpeedShedsEveryOneOfItsSettleSymbols() {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            long begins = SPIN_MILLIS + ReelMotion.STOP_STAGGER_MILLIS * reel;
            double from = at(reel, begins).symbols();
            double to = at(reel, SPIN_MILLIS + ReelMotion.LANDING_MILLIS).symbols();
            assertTrue(to - from > ReelMotion.SETTLE_SYMBOLS,
                    "reel " + reel + " only coasted " + (to - from) + " symbols");
        }
    }

    /**
     * A spin so short that a reel is told to stop while it is still coming up
     * to speed must still slow from the speed it had, not from the cruise.
     */
    @Test
    void aSpinShorterThanTheRampStillSlowsFromItsOwnSpeed() {
        long spin = ReelMotion.SPIN_UP_MILLIS / 3;
        double before = ReelMotion.of(0, spin - 2, -1).symbols();
        double at = ReelMotion.of(0, spin, -1).symbols();
        double after = ReelMotion.of(0, spin + 2, 2).symbols();
        double rateBefore = (at - before) / 2;
        double rateAfter = (after - at) / 2;
        assertEquals(1.0, rateAfter / rateBefore, 0.1,
                "slowdown starts at " + rateAfter + " from " + rateBefore);
    }
}
