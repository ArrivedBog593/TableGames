package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseExposureTest {

    private static final long CEILING = 50_000;

    @Test
    void nothingCommittedToStartWith() {
        HouseExposure exposure = new HouseExposure();
        assertEquals(0, exposure.total());
        assertEquals(0, exposure.tableCount());
    }

    @Test
    void oneTableMayTakeTheWholeCeiling() {
        HouseExposure exposure = new HouseExposure();
        assertTrue(exposure.fits("a", CEILING, CEILING));
        exposure.commit("a", CEILING);
        assertEquals(CEILING, exposure.total());
    }

    @Test
    void aSecondTableCannotTakeWhatTheFirstAlreadyHas() {
        // The bug this class exists for: every table used to be told it had
        // the whole exposure to itself, so eight of them could commit eight
        // times what the bankroll was ever meant to risk.
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", 40_000);
        assertFalse(exposure.fits("b", 20_000, CEILING));
        assertTrue(exposure.fits("b", 10_000, CEILING));
    }

    @Test
    void aTableReplacesItsOwnCommitmentRatherThanAddingToIt() {
        // A table reports the whole liability of its round each time, not the
        // increment. Adding would double-count everything already down.
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", 10_000);
        exposure.commit("a", 15_000);
        assertEquals(15_000, exposure.total());
        assertEquals(1, exposure.tableCount());
    }

    @Test
    void aTableIsNotBlockedByItsOwnExistingCommitment() {
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", CEILING);
        // Raising its own stake from 50,000 to 50,000 is not a new 50,000.
        assertTrue(exposure.fits("a", CEILING, CEILING));
        assertFalse(exposure.fits("a", CEILING + 1, CEILING));
    }

    @Test
    void releasingFreesTheRoomForOthers() {
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", 40_000);
        assertFalse(exposure.fits("b", 20_000, CEILING));
        exposure.release("a");
        assertTrue(exposure.fits("b", 20_000, CEILING));
        assertEquals(0, exposure.total());
    }

    @Test
    void committingZeroIsTheSameAsReleasing() {
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", 10_000);
        exposure.commit("a", 0);
        assertEquals(0, exposure.tableCount());
    }

    @Test
    void releasingATableThatNeverCommittedIsHarmless() {
        HouseExposure exposure = new HouseExposure();
        exposure.release("nobody");
        assertEquals(0, exposure.total());
    }

    @Test
    void manyTablesSumTogether() {
        HouseExposure exposure = new HouseExposure();
        for (int i = 0; i < 8; i++) {
            exposure.commit("table" + i, 5_000);
        }
        assertEquals(40_000, exposure.total());
        assertEquals(8, exposure.tableCount());
        assertTrue(exposure.fits("table8", 10_000, CEILING));
        assertFalse(exposure.fits("table8", 10_001, CEILING));
    }

    @Test
    void clearEmptiesEverything() {
        HouseExposure exposure = new HouseExposure();
        exposure.commit("a", 10_000);
        exposure.commit("b", 10_000);
        exposure.clear();
        assertEquals(0, exposure.total());
    }

    @Test
    void aClosedBankrollAllowsNothing() {
        HouseExposure exposure = new HouseExposure();
        assertFalse(exposure.fits("a", 1, 0));
        assertTrue(exposure.fits("a", 0, 0));
    }

    @Test
    void negativeExposureIsRejected() {
        HouseExposure exposure = new HouseExposure();
        assertThrows(IllegalArgumentException.class, () -> exposure.commit("a", -1));
        assertThrows(IllegalArgumentException.class, () -> exposure.fits("a", -1, CEILING));
    }
}
