package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackjackTournamentTest {

    private static final UUID ANA = UUID.nameUUIDFromBytes("ana".getBytes());
    private static final UUID LUIS = UUID.nameUUIDFromBytes("luis".getBytes());
    private static final UUID SOFIA = UUID.nameUUIDFromBytes("sofia".getBytes());

    /** Three players, 500 to enter, 1000 chips each, ten hands. */
    private static BlackjackTournament threeOf(PrizeSplit split) {
        return new BlackjackTournament(List.of(ANA, LUIS, SOFIA), 500, 1000, 10, split);
    }

    private static void hand(BlackjackTournament tournament, long ana, long luis, long sofia) {
        tournament.startHand();
        tournament.recordHand(Map.of(ANA, ana, LUIS, luis, SOFIA, sofia));
    }

    private static long prizeOf(BlackjackTournament tournament, UUID player) {
        return tournament.standings().stream()
                .filter(standing -> standing.playerId().equals(player))
                .mapToLong(BlackjackTournament.Standing::prize).findFirst().orElseThrow();
    }

    private static long deltaOf(Outcome outcome, UUID player) {
        return outcome.payouts().stream().filter(payout -> payout.playerId().equals(player))
                .mapToLong(Payout::delta).findFirst().orElseThrow();
    }

    @Test
    void theMostChipsWhenTheHandsRunOutTakesThePot() {
        BlackjackTournament tournament = new BlackjackTournament(List.of(ANA, LUIS, SOFIA), 500, 1000, 2,
                PrizeSplit.WINNER_TAKES_ALL);
        hand(tournament, 200, -100, 50);
        assertFalse(tournament.isOver());
        hand(tournament, 100, 300, -50);
        assertTrue(tournament.isOver());

        // Ana 1300, Luis 1200, Sofia 1000.
        assertEquals(1500, prizeOf(tournament, ANA));
        Outcome outcome = tournament.outcome();
        assertEquals(1000, deltaOf(outcome, ANA));
        assertEquals(-500, deltaOf(outcome, LUIS));
        assertEquals(-500, deltaOf(outcome, SOFIA));
        assertTrue(outcome.isZeroSum());
    }

    @Test
    void itEndsAtOnceWhenOnlyOnePlayerIsLeft() {
        BlackjackTournament tournament = threeOf(PrizeSplit.WINNER_TAKES_ALL);
        hand(tournament, 100, -1000, -1000);

        assertTrue(tournament.isOver());
        assertEquals(List.of(ANA), tournament.stillIn());
        assertEquals(1500, prizeOf(tournament, ANA));
    }

    @Test
    void playersOutOnTheSameHandRankByWhatTheyHeldGoingIntoIt() {
        BlackjackTournament tournament = threeOf(PrizeSplit.WINNER_TAKES_ALL);
        // Luis drops to 300 first. Then everybody goes out together.
        hand(tournament, 0, -700, 0);
        hand(tournament, -1000, -300, -1000);

        assertTrue(tournament.isOver());
        assertTrue(tournament.stillIn().isEmpty());
        // Ana and Sofia went in with 1000 and tie; Luis with 300 is last.
        assertEquals(750, prizeOf(tournament, ANA));
        assertEquals(750, prizeOf(tournament, SOFIA));
        assertEquals(0, prizeOf(tournament, LUIS));
        assertTrue(tournament.outcome().isZeroSum());
    }

    @Test
    void theLaterOfTwoEliminationsRanksHigher() {
        BlackjackTournament tournament = new BlackjackTournament(List.of(ANA, LUIS, SOFIA), 500, 1000, 3,
                PrizeSplit.SEVENTY_THIRTY);
        hand(tournament, 0, -1000, 0);
        hand(tournament, 500, 0, -1000);

        assertTrue(tournament.isOver());
        assertEquals(1050, prizeOf(tournament, ANA));
        assertEquals(450, prizeOf(tournament, SOFIA));
        assertEquals(0, prizeOf(tournament, LUIS));
    }

    @Test
    void whatATieCannotDivideGoesToTheSeatNearestTheDealersLeft() {
        BlackjackTournament tournament = new BlackjackTournament(List.of(ANA, LUIS, SOFIA), 333, 1000, 1,
                PrizeSplit.WINNER_TAKES_ALL);
        hand(tournament, 0, 0, 0);

        // 999 among three level players: 333 each, nothing over.
        assertEquals(333, prizeOf(tournament, ANA));

        BlackjackTournament two = new BlackjackTournament(List.of(ANA, LUIS, SOFIA), 1, 1000, 1,
                PrizeSplit.WINNER_TAKES_ALL);
        two.startHand();
        two.recordHand(Map.of(ANA, -500L, LUIS, 0L, SOFIA, 0L));
        // Luis and Sofia share 3: Luis sits first, and takes the odd one.
        assertEquals(2, prizeOf(two, LUIS));
        assertEquals(1, prizeOf(two, SOFIA));
        assertTrue(two.outcome().isZeroSum());
    }

    @Test
    void aWithdrawnPlayerRanksLastAndTheirEntryStaysInThePot() {
        BlackjackTournament tournament = threeOf(PrizeSplit.SEVENTY_THIRTY);
        hand(tournament, -900, 0, 0);
        tournament.withdraw(LUIS);
        assertFalse(tournament.isIn(LUIS));
        assertTrue(tournament.hasWithdrawn(LUIS));

        hand(tournament, 0, 0, -100);
        for (int i = 0; i < 8; i++) {
            hand(tournament, 0, 0, 0);
        }
        assertTrue(tournament.isOver());
        // Sofia 900, Ana 100, Luis withdrew with 1000.
        assertEquals(1050, prizeOf(tournament, SOFIA));
        assertEquals(450, prizeOf(tournament, ANA));
        assertEquals(0, prizeOf(tournament, LUIS));
        assertEquals(-500, deltaOf(tournament.outcome(), LUIS));
    }

    @Test
    void aTournamentForFunMovesNothing() {
        BlackjackTournament tournament = new BlackjackTournament(List.of(ANA, LUIS), 0, 1000, 1,
                PrizeSplit.WINNER_TAKES_ALL);
        tournament.startHand();
        tournament.recordHand(Map.of(ANA, 500L, LUIS, -500L));

        for (Payout payout : tournament.outcome().payouts()) {
            assertEquals(0, payout.delta());
        }
    }

    @Test
    void prizesAlwaysAddUpToThePot() {
        for (long pot : List.of(0L, 1L, 99L, 100L, 1001L, 123_456_789L)) {
            for (PrizeSplit split : PrizeSplit.values()) {
                assertEquals(pot, split.prizesFor(pot).stream().mapToLong(Long::longValue).sum());
            }
        }
        assertEquals(List.of(700L, 300L), PrizeSplit.SEVENTY_THIRTY.prizesFor(1000));
    }
}
