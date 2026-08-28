package com.github.arrivedbog593.tablegames.engine.economy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPlanTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static TransactionRecord entry(long seq, TransactionType type,
                                           UUID owner, long delta, long balance) {
        return new TransactionRecord(seq, "2026-08-26T00:00:00Z", type, owner, delta, balance, "");
    }

    // --- Line round trip ---------------------------------------------------

    @Test
    void aRecordSurvivesWritingAndReading() {
        TransactionRecord original = new TransactionRecord(
                42, "2026-08-26T12:00:00Z", TransactionType.PAYOUT,
                ALICE, 350, 1350, "roulette table 3");

        assertEquals(original, TransactionRecord.parse(original.toLine()).orElseThrow());
    }

    @Test
    void tabsInDetailCannotBreakTheFormat() {
        TransactionRecord record = new TransactionRecord(
                1, "t", TransactionType.BET, ALICE, -10, 90, "a\tb\tc");
        assertEquals(7, record.toLine().split("\t", -1).length);
        assertTrue(TransactionRecord.parse(record.toLine()).isPresent());
    }

    @Test
    void malformedLinesAreIgnoredRatherThanThrown() {
        // A crash can truncate the final line mid-write, so this is expected.
        assertTrue(TransactionRecord.parse("").isEmpty());
        assertTrue(TransactionRecord.parse("47\t2026").isEmpty());
        assertTrue(TransactionRecord.parse("notanumber\tt\tbet\t"
                + ALICE + "\t-10\t90\t").isEmpty());
        assertTrue(TransactionRecord.parse("1\tt\tunknown_type\t"
                + ALICE + "\t-10\t90\t").isEmpty());
    }

    @Test
    void parseHandlesAnEmptyDetailField() {
        Optional<TransactionRecord> parsed = TransactionRecord.parse(
                "5\t2026-08-26T00:00:00Z\tbet\t" + ALICE + "\t-10\t90\t");
        assertTrue(parsed.isPresent());
        assertEquals("", parsed.get().detail());
    }

    // --- Deciding what to restore -------------------------------------------

    @Test
    void nothingToDoWhenTheLogMatchesTheSave() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(9, TransactionType.BET, ALICE, -50, 950),
                entry(10, TransactionType.PAYOUT, ALICE, 100, 1050)));

        assertTrue(plan.isEmpty());
        assertEquals(0, plan.consideredCount());
        assertEquals(10, plan.highestSequence());
    }

    @Test
    void unappliedMovementsBecomeADelta() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.BET, ALICE, -50, 950),
                entry(12, TransactionType.PAYOUT, ALICE, 200, 1150)));

        assertEquals(150, plan.deltas().get(ALICE), "the two movements sum");
        assertEquals(12, plan.highestSequence());
        assertEquals(2, plan.appliedCount());
    }

    @Test
    void outOfOrderLinesStillSumCorrectly() {
        RecoveryPlan plan = RecoveryPlan.build(0, List.of(
                entry(3, TransactionType.PAYOUT, ALICE, 100, 300),
                entry(1, TransactionType.PAYOUT, ALICE, 100, 100),
                entry(2, TransactionType.PAYOUT, ALICE, 100, 200)));

        assertEquals(300, plan.deltas().get(ALICE));
    }

    @Test
    void movementsThatCancelOutLeaveNothingToDo() {
        RecoveryPlan plan = RecoveryPlan.build(0, List.of(
                entry(1, TransactionType.BET, ALICE, -100, 900),
                entry(2, TransactionType.PAYOUT, ALICE, 100, 1000)));

        assertTrue(plan.isEmpty());
        assertEquals(2, plan.appliedCount(), "they were still considered");
    }

    // --- The item rule --------------------------------------------------------

    @Test
    void aConversionIsDroppedButTheRestIsKept() {
        // The whole point of switching to per-transaction filtering: the grant
        // comes back, the conversion does not, and the player keeps the ingots
        // the rollback returned to them.
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.ADMIN_GIVE, ALICE, 5000, 5000),
                entry(12, TransactionType.CONVERT_IN, ALICE, 81, 5081)));

        assertEquals(5000, plan.deltas().get(ALICE));
        assertEquals(1, plan.appliedCount());
        assertEquals(1, plan.discardedCount());
    }

    @Test
    void aRedeemIsDroppedSoTheCreditsRevertWithTheItems() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.CONVERT_OUT, ALICE, -10_000, 0)));

        assertTrue(plan.isEmpty(), "the items vanished with the rollback, so must the cost");
        assertEquals(1, plan.discardedCount());
    }

    @Test
    void aShopPurchaseIsDropped() {
        RecoveryPlan plan = RecoveryPlan.build(0, List.of(
                entry(1, TransactionType.SHOP_PURCHASE, ALICE, -20_000, 0)));

        assertTrue(plan.isEmpty());
        assertEquals(1, plan.discardedCount());
    }

    @Test
    void onePlayerBeingFilteredDoesNotAffectAnother() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.CONVERT_OUT, ALICE, -500, 500),
                entry(12, TransactionType.PAYOUT, BOB, 300, 1300)));

        assertEquals(300, plan.deltas().get(BOB));
        assertFalse(plan.deltas().containsKey(ALICE));
        assertEquals(1, plan.affectedCount());
    }

    // --- The house -------------------------------------------------------------

    @Test
    void theHouseIsTrackedSeparatelyFromPlayers() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.RAKE, TransactionRecord.HOUSE, 50, 5050),
                entry(12, TransactionType.PAYOUT, ALICE, 200, 1200)));

        assertEquals(50, plan.houseDelta());
        assertEquals(200, plan.deltas().get(ALICE));
        assertFalse(plan.deltas().containsKey(TransactionRecord.HOUSE),
                "the house must not appear among player accounts");
        assertEquals(2, plan.affectedCount());
    }

    @Test
    void houseMovementsThatMovedItemsAreDroppedToo() {
        RecoveryPlan plan = RecoveryPlan.build(10, List.of(
                entry(11, TransactionType.SHOP_PURCHASE, TransactionRecord.HOUSE, 500, 5500)));

        assertEquals(0, plan.houseDelta());
        assertTrue(plan.isEmpty());
    }

    // --- Edges -------------------------------------------------------------------

    @Test
    void anEmptyLogYieldsAnEmptyPlan() {
        RecoveryPlan plan = RecoveryPlan.build(47, List.of());
        assertTrue(plan.isEmpty());
        assertEquals(47, plan.highestSequence(), "the sequence must not go backwards");
    }

    @Test
    void everyRecoverableTypeIsItemFree() {
        // The flag is the whole safety argument, so pin it down.
        for (TransactionType type : TransactionType.values()) {
            boolean touchesItems = type == TransactionType.CONVERT_IN
                    || type == TransactionType.CONVERT_OUT
                    || type == TransactionType.SHOP_PURCHASE;
            assertEquals(!touchesItems, type.isRecoverable(),
                    type + " recoverability does not match whether it moves items");
        }
    }

    @Test
    void everyTypeCodeParsesBack() {
        for (TransactionType type : TransactionType.values()) {
            assertEquals(type, TransactionType.fromCode(type.code()));
        }
        assertNull(TransactionType.fromCode("nonsense"));
    }
}