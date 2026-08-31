package com.github.arrivedbog593.tablegames.engine.table;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableOccupancyTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CARLA = UUID.nameUUIDFromBytes("carla".getBytes());

    /** Arrives and sits in one step, for tests that are not about the two-step. */
    private static void seat(TableOccupancy table, UUID player) {
        table.arrive(player);
        assertEquals(SeatChange.SEATED, table.sit(player, RoundPhase.IDLE));
    }

    @Test
    void openingATableMakesYouASpectator() {
        TableOccupancy table = new TableOccupancy(8);
        assertTrue(table.arrive(ALICE));
        assertTrue(table.isPresent(ALICE));
        assertFalse(table.isSeated(ALICE));
        assertEquals(1, table.spectatorCount());
        assertEquals(0, table.seatedCount());
    }

    @Test
    void arrivingTwiceIsHarmless() {
        TableOccupancy table = new TableOccupancy(8);
        assertTrue(table.arrive(ALICE));
        assertFalse(table.arrive(ALICE));
        assertEquals(1, table.spectatorCount());
    }

    @Test
    void sittingRequiresBeingAtTheTable() {
        TableOccupancy table = new TableOccupancy(8);
        assertEquals(SeatChange.NOT_AT_TABLE, table.sit(ALICE, RoundPhase.IDLE));
    }

    @Test
    void sittingMovesYouOutOfTheSpectators() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertEquals(0, table.spectatorCount());
        assertEquals(1, table.seatedCount());
        assertEquals(Set.of(ALICE), table.everyone());
    }

    @Test
    void sittingTwiceChangesNothing() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertEquals(SeatChange.ALREADY_SEATED, table.sit(ALICE, RoundPhase.IDLE));
        assertEquals(1, table.seatedCount());
    }

    @Test
    void aNinthPlayerIsRefusedAndStaysAWatcher() {
        // The crash this whole class exists to make impossible: a session is
        // built for eight seats and used to be handed one per bettor.
        TableOccupancy table = new TableOccupancy(8);
        for (int i = 0; i < 8; i++) {
            seat(table, UUID.nameUUIDFromBytes(("player" + i).getBytes()));
        }
        table.arrive(ALICE);
        assertEquals(SeatChange.TABLE_FULL, table.sit(ALICE, RoundPhase.IDLE));
        assertEquals(8, table.seatedCount());
        assertEquals(1, table.spectatorCount());
        assertTrue(table.isPresent(ALICE));
        assertFalse(table.hasFreeSeat());
    }

    @Test
    void aFreedSeatCanBeTakenByAWatcher() {
        TableOccupancy table = new TableOccupancy(2);
        seat(table, ALICE);
        seat(table, BOB);
        table.arrive(CARLA);
        assertEquals(SeatChange.TABLE_FULL, table.sit(CARLA, RoundPhase.IDLE));

        assertEquals(SeatChange.STOOD, table.stand(BOB, RoundPhase.IDLE));
        assertEquals(SeatChange.SEATED, table.sit(CARLA, RoundPhase.IDLE));
        assertEquals(List.of(ALICE, CARLA), table.seats());
    }

    @Test
    void standingPutsYouBackWithTheWatchers() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertEquals(SeatChange.STOOD, table.stand(ALICE, RoundPhase.OPEN));
        assertTrue(table.isPresent(ALICE));
        assertFalse(table.isSeated(ALICE));
        assertEquals(1, table.spectatorCount());
    }

    @Test
    void standingWhenNotSeatedIsRefused() {
        TableOccupancy table = new TableOccupancy(8);
        table.arrive(ALICE);
        assertEquals(SeatChange.NOT_SEATED, table.stand(ALICE, RoundPhase.IDLE));
    }

    @Test
    void sittingDownDuringTheLockoutIsAllowed() {
        // Taking a seat commits nothing, so there is nothing to escape from.
        // The newcomer simply cannot bet until the next round — a croupier
        // waving somebody to a chair after calling "no more bets".
        TableOccupancy table = new TableOccupancy(8);
        table.arrive(ALICE);
        assertEquals(SeatChange.SEATED, table.sit(ALICE, RoundPhase.LOCKED));
        assertTrue(table.isSeated(ALICE));
    }

    @Test
    void nobodyStandsUpOnceBettingHasClosed() {
        // The important half: leaving during the lockout would be a way to
        // pull a stake back out of a round that is about to resolve.
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertEquals(SeatChange.ROUND_LOCKED, table.stand(ALICE, RoundPhase.LOCKED));
        assertTrue(table.isSeated(ALICE));
    }

    @Test
    void aFullTableStillRefusesDuringTheLockout() {
        // The seat count still binds; only the phase check was relaxed.
        TableOccupancy table = new TableOccupancy(1);
        seat(table, BOB);
        table.arrive(ALICE);
        assertEquals(SeatChange.TABLE_FULL, table.sit(ALICE, RoundPhase.LOCKED));
    }

    @Test
    void seatChangesResumeWhileTheResultIsShown() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertEquals(SeatChange.STOOD, table.stand(ALICE, RoundPhase.RESULT));
    }

    @Test
    void departingWorksEvenDuringTheLockout() {
        // A disconnect is not a button that can be refused. It is survivable
        // instead: wagers settle against real balances without their owner.
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        assertTrue(table.depart(ALICE));
        assertFalse(table.isPresent(ALICE));
        assertEquals(0, table.seatedCount());
    }

    @Test
    void departingSomebodyWhoWasNeverThereReportsSo() {
        TableOccupancy table = new TableOccupancy(8);
        assertFalse(table.depart(ALICE));
    }

    @Test
    void departingClearsTheirReadyFlag() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        seat(table, BOB);
        table.setReady(ALICE, true);
        table.depart(ALICE);
        assertFalse(table.allSeatedReady());
        assertTrue(table.setReady(BOB, true));
        assertTrue(table.allSeatedReady());
    }

    @Test
    void seatOrderIsTurnOrder() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, CARLA);
        seat(table, ALICE);
        seat(table, BOB);
        assertEquals(List.of(CARLA, ALICE, BOB), table.seats());
        assertEquals(1, table.seatIndexOf(ALICE).orElseThrow());
        assertTrue(table.seatIndexOf(UUID.randomUUID()).isEmpty());
        assertEquals(BOB, table.seatAt(2).orElseThrow());
        assertTrue(table.seatAt(9).isEmpty());
    }

    @Test
    void anEmptyTableIsNeverUnanimous() {
        TableOccupancy table = new TableOccupancy(8);
        assertFalse(table.allSeatedReady());
        table.arrive(ALICE);
        assertFalse(table.allSeatedReady(), "A watcher is not a vote");
    }

    @Test
    void readyCountsEverySeatedPlayerNotOnlyTheBettors() {
        // One player betting a token amount and declaring ready must not be
        // unanimous on their own while others are still deciding.
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        seat(table, BOB);
        assertTrue(table.setReady(ALICE, true));
        assertFalse(table.allSeatedReady());
        table.setReady(BOB, true);
        assertTrue(table.allSeatedReady());
    }

    @Test
    void aWatcherCannotVote() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        table.arrive(BOB);
        assertFalse(table.setReady(BOB, true), "A watcher has no vote to cast");
        assertTrue(table.setReady(ALICE, true));
        assertTrue(table.allSeatedReady());
    }

    @Test
    void changingYourMindUnsetsReady() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        table.setReady(ALICE, true);
        assertTrue(table.setReady(ALICE, false));
        assertFalse(table.allSeatedReady());
        assertFalse(table.isReady(ALICE));
    }

    @Test
    void standingUpWithdrawsTheVote() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        seat(table, BOB);
        table.setReady(ALICE, true);
        table.stand(ALICE, RoundPhase.OPEN);
        assertFalse(table.allSeatedReady());
        table.setReady(BOB, true);
        assertTrue(table.allSeatedReady());
    }

    @Test
    void clearingReadyResetsTheRound() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        table.setReady(ALICE, true);
        table.clearReady();
        assertFalse(table.allSeatedReady());
    }

    @Test
    void sittingOutARoundIsCounted() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        seat(table, BOB);
        table.noteRoundEnded(List.of(ALICE));
        assertEquals(0, table.idleRoundsOf(ALICE));
        assertEquals(1, table.idleRoundsOf(BOB));
        table.noteRoundEnded(List.of(ALICE));
        assertEquals(2, table.idleRoundsOf(BOB));
    }

    @Test
    void takingPartResetsTheIdleCount() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, BOB);
        table.noteRoundEnded(List.of());
        table.noteRoundEnded(List.of());
        assertEquals(2, table.idleRoundsOf(BOB));
        table.noteRoundEnded(List.of(BOB));
        assertEquals(0, table.idleRoundsOf(BOB));
    }

    @Test
    void clearEmptiesTheTable() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        table.arrive(BOB);
        table.setReady(ALICE, true);
        table.clear();
        assertTrue(table.isEmpty());
        assertFalse(table.allSeatedReady());
        assertEquals(0, table.spectatorCount());
    }

    @Test
    void rejectsATableWithNoSeats() {
        assertThrows(IllegalArgumentException.class, () -> new TableOccupancy(0));
    }

    // --- Absence and eviction ---------------------------------------------------

    /** A table whose absence clock is short enough to tick through in a test. */
    private static TableOccupancy withShortAbsence() {
        return new TableOccupancy(8, 2);
    }

    private static List<UUID> tickFor(TableOccupancy table, RoundPhase phase, int ticks) {
        List<UUID> evicted = new java.util.ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            evicted.addAll(table.tickAbsences(phase));
        }
        return evicted;
    }

    @Test
    void closingTheScreenKeepsTheSeat() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        assertTrue(table.markAbsent(ALICE));
        assertTrue(table.isSeated(ALICE), "A misclick must not cost a seat");
        assertTrue(table.isAbsent(ALICE));
        assertEquals(2, table.absenceSecondsLeft(ALICE));
    }

    @Test
    void goingAbsentMarksYouReady() {
        // Otherwise one empty chair holds the ready button hostage for the
        // whole table until the absence clock runs out.
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        seat(table, BOB);
        table.setReady(BOB, true);
        assertFalse(table.allSeatedReady());
        table.markAbsent(ALICE);
        assertTrue(table.allSeatedReady());
    }

    @Test
    void anAbsentPlayerIsNotAmongTheWatchers() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        assertEquals(0, table.spectatorCount());
        assertEquals(1, table.seatedCount());
    }

    @Test
    void markingAWatcherAbsentJustRemovesThem() {
        TableOccupancy table = withShortAbsence();
        table.arrive(ALICE);
        assertFalse(table.markAbsent(ALICE));
        assertFalse(table.isPresent(ALICE));
    }

    @Test
    void reopeningTheTableCancelsTheEviction() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        tickFor(table, RoundPhase.OPEN, 20);
        table.arrive(ALICE);
        assertFalse(table.isAbsent(ALICE));
        assertTrue(table.isSeated(ALICE));
        assertTrue(tickFor(table, RoundPhase.OPEN, 200).isEmpty());
    }

    @Test
    void theClockRestartsFromTheTopOnTheNextAbsence() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        tickFor(table, RoundPhase.OPEN, 30);
        table.arrive(ALICE);
        table.markAbsent(ALICE);
        assertEquals(2, table.absenceSecondsLeft(ALICE));
    }

    @Test
    void runningOutFreesTheSeat() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        seat(table, BOB);
        table.markAbsent(ALICE);
        List<UUID> evicted = tickFor(table, RoundPhase.OPEN, 40);
        assertEquals(List.of(ALICE), evicted);
        assertFalse(table.isSeated(ALICE));
        assertFalse(table.isPresent(ALICE), "An evicted player is gone, not watching");
        assertEquals(List.of(BOB), table.seats());
    }

    @Test
    void anEvictionComingDueDuringTheLockoutIsHeld() {
        // Standing a player up mid-lockout is exactly the move the lockout
        // forbids: it would pull a live stake out of a resolving round.
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        assertTrue(tickFor(table, RoundPhase.LOCKED, 60).isEmpty());
        assertTrue(table.isSeated(ALICE));
        assertTrue(table.isAbsent(ALICE), "Still pending, not forgotten");
    }

    @Test
    void aHeldEvictionLandsWhenTheRoundResolves() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        tickFor(table, RoundPhase.LOCKED, 60);
        assertEquals(List.of(ALICE), tickFor(table, RoundPhase.RESULT, 1));
        assertFalse(table.isSeated(ALICE));
    }

    @Test
    void comingBackDuringAHeldEvictionSavesTheSeat() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        tickFor(table, RoundPhase.LOCKED, 60);
        table.arrive(ALICE);
        assertTrue(tickFor(table, RoundPhase.RESULT, 20).isEmpty());
        assertTrue(table.isSeated(ALICE));
    }

    @Test
    void standingUpClearsTheAbsence() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        table.markAbsent(ALICE);
        table.arrive(ALICE);
        table.stand(ALICE, RoundPhase.OPEN);
        assertFalse(table.isAbsent(ALICE));
        assertTrue(tickFor(table, RoundPhase.OPEN, 200).isEmpty());
    }

    @Test
    void anEvictedSeatCanBeTakenImmediately() {
        TableOccupancy table = new TableOccupancy(1, 2);
        seat(table, ALICE);
        table.arrive(BOB);
        assertEquals(SeatChange.TABLE_FULL, table.sit(BOB, RoundPhase.OPEN));
        table.markAbsent(ALICE);
        tickFor(table, RoundPhase.OPEN, 40);
        assertEquals(SeatChange.SEATED, table.sit(BOB, RoundPhase.OPEN));
    }

    @Test
    void tickingWithNobodyAbsentCostsNothing() {
        TableOccupancy table = withShortAbsence();
        seat(table, ALICE);
        assertTrue(tickFor(table, RoundPhase.OPEN, 100).isEmpty());
        assertTrue(table.isSeated(ALICE));
    }

    @Test
    void ninetySecondsIsTheDefault() {
        TableOccupancy table = new TableOccupancy(8);
        seat(table, ALICE);
        table.markAbsent(ALICE);
        assertEquals(TableOccupancy.DEFAULT_ABSENCE_SECONDS, table.absenceSecondsLeft(ALICE));
    }

    @Test
    void rejectsANonsensicalAbsenceClock() {
        assertThrows(IllegalArgumentException.class, () -> new TableOccupancy(8, 0));
    }
}