package com.github.arrivedbog593.tablegames.platform.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WheelMotionTest {

    /** Three turns of a European wheel and then the pocket: the fewest and the most. */
    private static final double[] TOTALS = {3 * 37, 3 * 37 + 36};

    /** How long the screen then holds the winner lit. */
    private static final long SETTLE_MILLIS = 2200;

    private static double at(long millis, double total) {
        return WheelMotion.pocketsAt(millis, total);
    }

    /** Pockets per millisecond, signed: negative while it swings back. */
    private static double speed(long millis, double total) {
        return at(millis + 1, total) - at(millis, total);
    }

    /** Forward all the way until the first fret turns it back. */
    @Test
    void itOnlyRunsForwardUntilTheFirstBounce() {
        for (double total : TOTALS) {
            double last = -1;
            for (long t = 0; t <= WheelMotion.HIT_MILLIS[0]; t++) {
                double now = at(t, total);
                assertTrue(now >= last - 1e-9, "went back at " + t + " ms");
                last = now;
            }
        }
    }

    /** It comes to rest on the winner exactly, which is what lights it. */
    @Test
    void itStopsExactlyOnTheWinningPocket() {
        for (double total : TOTALS) {
            assertEquals(total, at(WheelMotion.SPIN_MILLIS, total), 1e-9);
            assertEquals(total, at(WheelMotion.SPIN_MILLIS - 1, total), 0.01);
        }
    }

    /** A ball is thrown, not started: fastest on the first frame, slower on every one after. */
    @Test
    void itIsFastestAtTheThrowAndOnlySlowsUntilItBounces() {
        for (double total : TOTALS) {
            double last = Double.MAX_VALUE;
            for (long t = 0; t < WheelMotion.HIT_MILLIS[0] - 1; t += 5) {
                double now = speed(t, total);
                assertTrue(now <= last + 1e-6, "sped up at " + t + " ms");
                last = now;
            }
        }
    }

    /** No stretch hands the next a speed it did not have: off the rim, and onto the winner. */
    @Test
    void theSpeedCarriesAcrossEveryStretch() {
        for (double total : TOTALS) {
            for (double seam : new double[]{WheelMotion.RIM_MILLIS, WheelMotion.ARRIVE_AT}) {
                long s = (long) seam;
                double before = speed(s - 2, total);
                double after = speed(s + 1, total);
                assertEquals(1.0, after / before, 0.05, "jolts at " + s + " ms");
            }
        }
    }

    /** The roll is slow enough to read each pocket go by, and long enough to watch. */
    @Test
    void theRollIsSlowEnoughToRead() {
        assertTrue(WheelMotion.ROLL_MILLIS >= 600, "roll lasts " + WheelMotion.ROLL_MILLIS);
        for (double total : TOTALS) {
            for (long t = (long) WheelMotion.RIM_MILLIS + 1; t < WheelMotion.ARRIVE_AT; t += 10) {
                assertTrue(speed(t, total) * 1000 <= 9.5,
                        "rolling at " + speed(t, total) * 1000 + " pockets a second");
            }
        }
    }

    /**
     * It turns at each fret it strikes — the moment the sound clacks — and
     * never strays further than the overshoot, each swing smaller than the one
     * before.
     */
    @Test
    void itBouncesAtEachHitAndEachSwingIsSmaller() {
        for (double total : TOTALS) {
            double previousReach = Double.MAX_VALUE;
            for (int i = 0; i < WheelMotion.HIT_MILLIS.length; i++) {
                long hit = WheelMotion.HIT_MILLIS[i];
                double before = speed(hit - 6, total);
                double after = speed(hit + 5, total);
                assertTrue(before * after < 0, "does not turn at hit " + i + " (" + hit + " ms)");
                double reach = Math.abs(at(hit, total) - total);
                assertTrue(reach < previousReach, "swing " + i + " is not smaller");
                assertTrue(reach <= WheelMotion.OVERSHOOT + 1e-9);
                previousReach = reach;
            }
            // The first bounce carries it most of the way onto the next pocket.
            assertTrue(at(WheelMotion.HIT_MILLIS[0], total) - total > 0.5);
        }
    }

    /** The spin and the lit winner fit inside the six seconds the server holds a result, with room. */
    @Test
    void theSpinAndTheLitWinnerFitInsideTheServersResult() {
        assertTrue(WheelMotion.SPIN_MILLIS + SETTLE_MILLIS <= 5_900,
                "spin runs " + WheelMotion.SPIN_MILLIS);
    }
}
