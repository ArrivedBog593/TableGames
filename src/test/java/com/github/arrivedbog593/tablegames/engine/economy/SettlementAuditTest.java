package com.github.arrivedbog593.tablegames.engine.economy;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettlementAuditTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());

    private static final HouseBankroll RICH = HouseBankroll.of(1_000_000);
    private static final HouseBankroll BROKE = HouseBankroll.of(100);

    private static Outcome outcome(long rake, List<UUID> winners, Payout... payouts) {
        return new Outcome(List.of(payouts), winners, rake, "test");
    }

    // --- Player versus player -------------------------------------------------

    @Test
    void aBalancedPvpHandIsApproved() {
        // Alice wins 100 from Bob. Nothing created, nothing destroyed.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, 100), new Payout(BOB, -100)),
                false, RICH);

        assertTrue(verdict.approved());
        assertEquals(0, verdict.houseDelta(), "with no rake the house takes nothing");
    }

    @Test
    void rakeGoesToTheHouseAndStillBalances() {
        // A pot of 200 with a 10 rake: Alice takes 90 net, Bob loses 100.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(10, List.of(ALICE),
                        new Payout(ALICE, 90), new Payout(BOB, -100)),
                false, RICH);

        assertTrue(verdict.approved());
        assertEquals(10, verdict.houseDelta());
    }

    @Test
    void aPvpHandThatCreatesCreditsIsRefused() {
        // Alice gains 200 but Bob only lost 100. Fifty credits from nowhere.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, 200), new Payout(BOB, -100)),
                false, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.not_zero_sum", verdict.reasonKey());
    }

    @Test
    void aPvpHandThatDestroysCreditsIsAlsoRefused() {
        // Value vanishing is as much a bug as value appearing, and players
        // notice the missing kind faster.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, 50), new Payout(BOB, -100)),
                false, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.not_zero_sum", verdict.reasonKey());
    }

    @Test
    void undeclaredRakeIsRefused() {
        // The house would gain 40 while declaring only 10. The missing 30 is
        // exactly the sort of quiet skim this check exists to catch.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(10, List.of(ALICE),
                        new Payout(ALICE, 60), new Payout(BOB, -100)),
                false, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.not_zero_sum", verdict.reasonKey());
    }

    @Test
    void aSplitPotBalances() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE, BOB),
                        new Payout(ALICE, 50), new Payout(BOB, 50), new Payout(CAROL, -100)),
                false, RICH);

        assertTrue(verdict.approved());
    }

    // --- House banked ------------------------------------------------------------

    @Test
    void theHousePayingAWinnerIsApprovedWhenItCanAfford() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE), new Payout(ALICE, 3_500)),
                true, RICH);

        assertTrue(verdict.approved());
        assertEquals(-3_500, verdict.houseDelta());
        assertTrue(verdict.housePays());
    }

    @Test
    void theHouseKeepingALosingBetIsApproved() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(), new Payout(ALICE, -200)),
                true, RICH);

        assertTrue(verdict.approved());
        assertEquals(200, verdict.houseDelta());
        assertFalse(verdict.housePays());
    }

    @Test
    void aHouseBankedHandNeedNotBeZeroSum() {
        // This is the whole difference between the two kinds of game: here the
        // house is a real counterparty, so credits legitimately move in and out.
        Outcome payout = outcome(0, List.of(ALICE), new Payout(ALICE, 3_500));
        assertFalse(payout.isZeroSum());
        assertTrue(SettlementAudit.audit(payout, true, RICH).approved());
    }

    @Test
    void theHouseCannotPayWhatItDoesNotHold() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE), new Payout(ALICE, 3_500)),
                true, BROKE);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.house_cannot_cover", verdict.reasonKey());
        assertEquals(3_400, verdict.shortfall(), "3,500 owed against 100 held");
    }

    @Test
    void payingExactlyTheWholeBankrollIsStillAllowed() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE), new Payout(ALICE, 100)),
                true, BROKE);

        assertTrue(verdict.approved(), "broke afterwards is not the same as overdrawn");
    }

    @Test
    void severalPlayersAtAHouseTableNetOut() {
        // Alice wins 200, Bob and Carol lose 100 each: the house breaks even.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, 200), new Payout(BOB, -100), new Payout(CAROL, -100)),
                true, RICH);

        assertTrue(verdict.approved());
        assertEquals(0, verdict.houseDelta());
    }

    // --- Malformed outcomes ---------------------------------------------------------

    @Test
    void twoEntriesForOnePlayerAreRefused() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, 100), new Payout(ALICE, 100), new Payout(BOB, -200)),
                false, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.duplicate_payout", verdict.reasonKey());
    }

    @Test
    void aWinnerWithNoPayoutEntryIsRefused() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(CAROL),
                        new Payout(ALICE, 100), new Payout(BOB, -100)),
                false, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.winner_not_paid", verdict.reasonKey());
    }

    @Test
    void anOverflowingTotalIsRefusedRatherThanWrapped() {
        // A wrapped sum looks perfectly balanced, which is the dangerous part.
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of(ALICE),
                        new Payout(ALICE, Long.MAX_VALUE), new Payout(BOB, Long.MAX_VALUE)),
                true, RICH);

        assertFalse(verdict.approved());
        assertEquals("tablegames.settle.arithmetic_overflow", verdict.reasonKey());
    }

    @Test
    void anEmptyOutcomeIsHarmless() {
        SettlementAudit.Verdict verdict = SettlementAudit.audit(
                outcome(0, List.of()), false, RICH);

        assertTrue(verdict.approved());
        assertEquals(0, verdict.houseDelta());
    }
}