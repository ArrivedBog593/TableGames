package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HouseBankrollTest {

    /** Roulette's straight-up bet, the worst case any table offers. */
    private static final int STRAIGHT_UP = 35;

    /** An even-money bet: red, black, odd, even. */
    private static final int EVEN_MONEY = 1;

    @Test
    void exposureIsTheConfiguredSliceOfTheBankroll() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);
        assertEquals(50_000, bankroll.maximumExposure(), "five percent of a million");
    }

    @Test
    void theMaximumBetAccountsForTheStakeComingBackToo() {
        // A winning straight-up returns the stake plus 35 times it, so the
        // house owes 36x, not 35x.
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);
        assertEquals(50_000 / 36, bankroll.maximumBet(STRAIGHT_UP));
    }

    @Test
    void saferBetsAreAllowedToBeLarger() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);
        assertTrue(bankroll.maximumBet(EVEN_MONEY) > bankroll.maximumBet(STRAIGHT_UP),
                "red pays 1 to 1, so the house can afford a bigger bet on it");
        assertEquals(25_000, bankroll.maximumBet(EVEN_MONEY));
    }

    @Test
    void aBigWinNeverTakesMoreThanTheExposureSlice() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);
        long worstCase = bankroll.maximumBet(STRAIGHT_UP) * (STRAIGHT_UP + 1L);
        assertTrue(worstCase <= bankroll.maximumExposure(),
                "the whole point: one bet cannot exceed the slice");
    }

    @Test
    void limitsTightenAsTheBankrollShrinks() {
        long rich = HouseBankroll.of(5_000_000).maximumBet(STRAIGHT_UP);
        long poor = HouseBankroll.of(500_000).maximumBet(STRAIGHT_UP);
        assertTrue(rich > poor, "a losing casino must quietly lower its own limits");
        assertEquals(rich / 10, poor, "and proportionally, since the rule is a percentage");
    }

    @Test
    void anEmptyBankrollClosesHouseGames() {
        HouseBankroll empty = HouseBankroll.of(0);
        assertFalse(empty.isOpen());
        assertEquals(HouseBankroll.Status.CLOSED, empty.status());
        assertEquals(0, empty.maximumBet(STRAIGHT_UP),
                "a closed casino offers no bets at all");
    }

    @Test
    void theReserveIsTheThresholdNotZero() {
        HouseBankroll justUnder = HouseBankroll.of(HouseBankroll.DEFAULT_MINIMUM_RESERVE - 1);
        HouseBankroll justOver = HouseBankroll.of(HouseBankroll.DEFAULT_MINIMUM_RESERVE);

        assertFalse(justUnder.isOpen());
        assertTrue(justOver.isOpen());
    }

    @Test
    void aThinBankrollIsOpenButFlagged() {
        HouseBankroll thin = HouseBankroll.of(HouseBankroll.DEFAULT_MINIMUM_RESERVE * 2);
        assertTrue(thin.isOpen());
        assertEquals(HouseBankroll.Status.LOW, thin.status(),
                "still trading, but the operator should hear about it");
    }

    @Test
    void aHealthyBankrollSaysSo() {
        assertEquals(HouseBankroll.Status.HEALTHY, HouseBankroll.of(1_000_000).status());
    }

    @Test
    void canCoverIsTheLastLineOfDefense() {
        HouseBankroll bankroll = HouseBankroll.of(1_000);
        assertTrue(bankroll.canCover(1_000));
        assertFalse(bankroll.canCover(1_001),
                "the house must never pay what it does not hold");
    }

    @Test
    void bankrollNeededForInvertsTheLimit() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);
        long needed = bankroll.bankrollNeededFor(10_000, STRAIGHT_UP);

        // 10,000 at 36x is 360,000 of exposure, which must be five percent.
        assertEquals(7_200_000, needed);
        assertTrue(bankroll.withBalance(needed).maximumBet(STRAIGHT_UP) >= 10_000,
                "the figure it names must actually allow the bet");
    }

    @Test
    void customLimitsAreRespected() {
        HouseBankroll cautious = new HouseBankroll(1_000_000, 1, 50_000);
        assertEquals(10_000, cautious.maximumExposure());
        assertFalse(new HouseBankroll(40_000, 1, 50_000).isOpen());
    }

    @Test
    void impossibleConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new HouseBankroll(-1, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> new HouseBankroll(100, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new HouseBankroll(100, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> new HouseBankroll(100, 5, -1));
        assertThrows(IllegalArgumentException.class,
                () -> HouseBankroll.of(1000).maximumBet(-1));
    }

    // --- The reserve follows what has been promised ------------------------------

    @Test
    void anEmptyRoomLeavesTheOperatorsFloorAlone() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);

        assertEquals(HouseBankroll.DEFAULT_MINIMUM_RESERVE,
                bankroll.withReserveAtLeast(0).minimumReserve());
    }

    @Test
    void aBusyRoomRaisesTheReserveToWhatItOwes() {
        // The point of the whole thing: ten thousand is a meaningless floor
        // beside a bank this size, and the number that actually matters is
        // what the tables are holding.
        HouseBankroll bankroll = HouseBankroll.of(100_000_000);

        assertEquals(5_000_000, bankroll.withReserveAtLeast(5_000_000).minimumReserve());
    }

    @Test
    void theHouseClosesWhenItCanNoLongerCoverWhatItPromised() {
        // Only reachable after paying out: what a table may commit is a slice
        // of the balance, so the two cross when the balance falls afterward.
        HouseBankroll afterPayouts = new HouseBankroll(3_000_000, 5, 10_000)
                .withReserveAtLeast(5_000_000);

        assertFalse(afterPayouts.isOpen());
        assertEquals(HouseBankroll.Status.CLOSED, afterPayouts.status());
        assertEquals(0, afterPayouts.maximumBet(35));
    }

    @Test
    void aReserveBelowTheFloorDoesNotLowerIt() {
        HouseBankroll bankroll = HouseBankroll.of(1_000_000);

        assertEquals(HouseBankroll.DEFAULT_MINIMUM_RESERVE,
                bankroll.withReserveAtLeast(500).minimumReserve());
    }

    @Test
    void raisingTheReserveChangesNothingElse() {
        HouseBankroll bankroll = new HouseBankroll(80_000_000, 7, 10_000);

        HouseBankroll raised = bankroll.withReserveAtLeast(1_000_000);

        assertEquals(bankroll.balance(), raised.balance());
        assertEquals(bankroll.exposurePercent(), raised.exposurePercent());
        assertEquals(bankroll.maximumExposure(), raised.maximumExposure());
    }
}