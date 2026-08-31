package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BettingWindowTest {

    /** Small window so a test can tick through it: 3s open, 2s locked, 1s result. */
    private static BettingWindow small() {
        return new BettingWindow(3, 2, 1);
    }

    /** Ticks until the given event, failing if it never arrives. */
    private static int tickUntil(BettingWindow window, BettingWindow.Event wanted) {
        for (int ticks = 1; ticks <= 10_000; ticks++) {
            if (window.tick() == wanted) {
                return ticks;
            }
        }
        throw new AssertionError("Never saw " + wanted);
    }

    @Test
    void startsIdle() {
        BettingWindow window = small();
        assertEquals(RoundPhase.IDLE, window.phase());
        assertFalse(window.isRunning());
        assertEquals(0, window.secondsRemaining());
    }

    @Test
    void idleTicksDoNothing() {
        BettingWindow window = small();
        assertEquals(BettingWindow.Event.NONE, window.tick());
        assertEquals(RoundPhase.IDLE, window.phase());
    }

    @Test
    void startingOpensBetting() {
        BettingWindow window = small();
        assertTrue(window.start());
        assertEquals(RoundPhase.OPEN, window.phase());
        assertEquals(5, window.secondsRemaining());
    }

    @Test
    void startingTwiceDoesNotExtendTheWindow() {
        BettingWindow window = small();
        window.start();
        for (int i = 0; i < 40; i++) {
            window.tick();
        }
        assertFalse(window.start());
        // Still, the original window, 40 ticks in — not restarted from the top.
        assertEquals(5 * 20 - 40, tickUntil(window, BettingWindow.Event.SPIN));
    }

    @Test
    void lockoutBeginsExactlyWhenTheOpenSecondsRunOut() {
        BettingWindow window = small();
        window.start();
        int ticks = tickUntil(window, BettingWindow.Event.LOCKED);
        assertEquals(3 * 20, ticks);
        assertEquals(RoundPhase.LOCKED, window.phase());
        assertEquals(2, window.secondsRemaining());
    }

    @Test
    void bettingIsRefusedOnTheVeryTickTheLockoutBegins() {
        BettingWindow window = small();
        window.start();
        tickUntil(window, BettingWindow.Event.LOCKED);
        // The event and the phase change land together, so a client warned by
        // this tick cannot slip a wager into the gap.
        assertFalse(window.phase().acceptsBets());
        assertFalse(window.phase().allowsStanding());
        // Sitting stays open: joining commits nothing, so there is nothing
        // to escape from by doing it late.
        assertTrue(window.phase().allowsSitting());
    }

    @Test
    void theWholeWindowIsOpenPlusLock() {
        BettingWindow window = small();
        window.start();
        assertEquals(5 * 20, tickUntil(window, BettingWindow.Event.SPIN));
    }

    @Test
    void spinLeavesTheClockStopped() {
        BettingWindow window = small();
        window.start();
        tickUntil(window, BettingWindow.Event.SPIN);
        assertEquals(RoundPhase.IDLE, window.phase());
        assertFalse(window.isRunning());
    }

    @Test
    void callNowCutsTheWindowShort() {
        BettingWindow window = small();
        window.start();
        window.tick();
        assertTrue(window.callNow());
        assertEquals(BettingWindow.Event.SPIN, window.tick());
    }

    @Test
    void callNowSkipsTheLockoutEntirely() {
        BettingWindow window = small();
        window.start();
        window.callNow();
        // Unanimity already produced the moment the lockout exists to force,
        // so it must not add five more seconds on top.
        assertEquals(BettingWindow.Event.SPIN, window.tick());
    }

    @Test
    void callNowOnAnIdleTableDoesNothing() {
        BettingWindow window = small();
        assertFalse(window.callNow());
        assertEquals(RoundPhase.IDLE, window.phase());
    }

    @Test
    void callNowWorksInsideTheLockoutToo() {
        BettingWindow window = small();
        window.start();
        tickUntil(window, BettingWindow.Event.LOCKED);
        assertTrue(window.callNow());
        assertEquals(BettingWindow.Event.SPIN, window.tick());
    }

    @Test
    void resultIsShownThenCleared() {
        BettingWindow window = small();
        window.start();
        window.showResult();
        assertEquals(RoundPhase.RESULT, window.phase());
        assertFalse(window.phase().acceptsBets());
        assertTrue(window.phase().allowsStanding());
        assertEquals(20, tickUntil(window, BettingWindow.Event.RESULT_CLEARED));
        assertEquals(RoundPhase.IDLE, window.phase());
    }

    @Test
    void aRoundCannotStartWhileTheResultIsUp() {
        BettingWindow window = small();
        window.showResult();
        assertFalse(window.start());
    }

    @Test
    void secondsRemainingRoundsUpSoItNeverReadsZeroWhileRunning() {
        BettingWindow window = small();
        window.start();
        for (int i = 0; i < 5 * 20 - 1; i++) {
            window.tick();
            assertTrue(window.secondsRemaining() >= 1,
                    "Countdown hit zero while still running");
        }
    }

    @Test
    void aSecondEventFiresOncePerSecond() {
        BettingWindow window = small();
        window.start();
        int seconds = 0;
        for (int i = 0; i < 5 * 20; i++) {
            if (window.tick() == BettingWindow.Event.SECOND_ELAPSED) {
                seconds++;
            }
        }
        // Five boundaries are crossed. Two are swallowed by LOCKED and SPIN,
        // which already mean "tell the clients", leaving three plain ones.
        assertEquals(3, seconds);
    }

    @Test
    void resetStopsEverything() {
        BettingWindow window = small();
        window.start();
        window.reset();
        assertEquals(RoundPhase.IDLE, window.phase());
        assertEquals(BettingWindow.Event.NONE, window.tick());
    }

    @Test
    void rejectsNonsensicalWindows() {
        assertThrows(IllegalArgumentException.class, () -> new BettingWindow(0, 5, 6));
        assertThrows(IllegalArgumentException.class, () -> new BettingWindow(25, -1, 6));
    }

    @Test
    void defaultWindowIsThirtySecondsWithAFiveSecondLockout() {
        BettingWindow window = new BettingWindow();
        window.start();
        assertEquals(30, window.secondsRemaining());
        assertEquals(25 * 20, tickUntil(window, BettingWindow.Event.LOCKED));
        assertEquals(5, window.secondsRemaining());
    }

}