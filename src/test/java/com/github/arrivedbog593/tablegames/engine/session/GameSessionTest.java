package com.github.arrivedbog593.tablegames.engine.session;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the shared session machinery through a deliberately trivial game.
 * The point is the plumbing — turn order, rejections, pot, zero-sum settling —
 * not any real ruleset.
 */
class GameSessionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID CAROL = UUID.nameUUIDFromBytes("carol".getBytes());
    private static final UUID STRANGER = UUID.nameUUIDFromBytes("stranger".getBytes());

    /** Everyone antes one credit; the first to act takes the pot. */
    private static final class TestSession extends GameSession {

        TestSession(List<Seat> seats, RandomGenerator random) {
            super(seats, random);
        }

        @Override
        protected void onBegin() {
            for (Seat seat : seats()) {
                seat.setStatus(SeatStatus.ACTIVE);
                seat.wager(1);
            }
            collectBets();
            setState(GameState.IN_PROGRESS);
            setTurn(seats().getFirst());
        }

        @Override
        public List<Action> legalActions(UUID playerId) {
            return currentTurn().filter(playerId::equals).isPresent()
                    ? List.of(new BettingAction.Fold(), new BettingAction.Check())
                    : List.of();
        }

        @Override
        protected ActionResult onAction(Seat seat, Action action) {
            if (action instanceof BettingAction.Fold) {
                seat.setStatus(SeatStatus.FOLDED);
                advanceTurn();
                return ActionResult.ok();
            }
            if (action instanceof BettingAction.Check) {
                settleTo(seat);
                return ActionResult.ok();
            }
            return ActionResult.illegalAction();
        }

        private void settleTo(Seat winner) {
            long prize = takePot();
            winner.award(prize);
            List<Payout> payouts = seats().stream()
                    .map(seat -> new Payout(
                            seat.playerId(),
                            seat.equals(winner) ? prize - 1 : -1))
                    .toList();
            finish(new Outcome(payouts, List.of(winner.playerId()), 0,
                    "tablegames.summary.test"));
        }
    }

    private static TestSession newSession() {
        return new TestSession(List.of(
                Seat.forPlayer(0, ALICE, 100),
                Seat.forPlayer(1, BOB, 100),
                Seat.forPlayer(2, CAROL, 100)), new Random(7L));
    }

    @Test
    void sessionStartsWaitingAndBecomesInProgress() {
        TestSession session = newSession();
        assertEquals(GameState.WAITING, session.state());
        session.begin();
        assertEquals(GameState.IN_PROGRESS, session.state());
        assertEquals(ALICE, session.currentTurn().orElseThrow());
    }

    @Test
    void anteMovesCreditsFromStacksIntoThePot() {
        TestSession session = newSession();
        session.begin();
        assertEquals(3, session.pot());
        assertEquals(99, session.seats().getFirst().credits());
    }

    @Test
    void actingOutOfTurnIsRejected() {
        TestSession session = newSession();
        session.begin();
        ActionResult result = session.submit(BOB, new BettingAction.Check());
        assertFalse(result.accepted());
        assertEquals("tablegames.reject.not_your_turn", result.messageKey());
    }

    @Test
    void unseatedPlayerIsRejected() {
        TestSession session = newSession();
        session.begin();
        assertEquals("tablegames.reject.not_seated",
                session.submit(STRANGER, new BettingAction.Check()).messageKey());
    }

    @Test
    void actionsBeforeBeginAreRejected() {
        TestSession session = newSession();
        assertEquals("tablegames.reject.wrong_state",
                session.submit(ALICE, new BettingAction.Check()).messageKey());
    }

    @Test
    void foldingPassesTheTurnAndSkipsFoldedSeats() {
        TestSession session = newSession();
        session.begin();

        assertTrue(session.submit(ALICE, new BettingAction.Fold()).accepted());
        assertEquals(BOB, session.currentTurn().orElseThrow());

        assertTrue(session.submit(BOB, new BettingAction.Fold()).accepted());
        assertEquals(CAROL, session.currentTurn().orElseThrow());

        assertEquals(1, session.activeSeats().size());
    }

    @Test
    void turnWrapsAroundTheTable() {
        TestSession session = newSession();
        session.begin();
        session.submit(ALICE, new BettingAction.Fold());
        session.submit(BOB, new BettingAction.Fold());
        // Carol is last; folding her wraps past the two folded seats and finds nobody.
        session.submit(CAROL, new BettingAction.Fold());
        assertTrue(session.currentTurn().isEmpty());
    }

    @Test
    void timeoutFoldsThePlayerOnTheClock() {
        TestSession session = newSession();
        session.begin();
        session.timeOutCurrentTurn();
        assertEquals(SeatStatus.FOLDED, session.seats().getFirst().status());
        assertEquals(BOB, session.currentTurn().orElseThrow());
    }

    @Test
    void finishedHandIsZeroSum() {
        TestSession session = newSession();
        session.begin();
        session.submit(ALICE, new BettingAction.Check());

        Outcome outcome = session.outcome().orElseThrow();
        assertEquals(GameState.FINISHED, session.state());
        assertTrue(outcome.isZeroSum(), "a PvP hand must not mint credits");
        assertEquals(List.of(ALICE), outcome.winners());
        assertEquals(102, session.seats().getFirst().credits());
    }

    @Test
    void noActionsAcceptedAfterTheHandEnds() {
        TestSession session = newSession();
        session.begin();
        session.submit(ALICE, new BettingAction.Check());
        assertEquals("tablegames.reject.wrong_state",
                session.submit(ALICE, new BettingAction.Fold()).messageKey());
    }

    @Test
    void cancellingRefundsEveryWager() {
        TestSession session = newSession();
        session.begin();
        session.cancel("tablegames.summary.cancelled");

        assertEquals(GameState.CANCELLED, session.state());
        assertEquals(0, session.pot());
        for (Seat seat : session.seats()) {
            assertEquals(100, seat.credits(), "everyone should be made whole");
        }
    }

    @Test
    void wagerIsCappedAtTheStackSoAllInWorks() {
        Seat seat = Seat.forPlayer(0, ALICE, 50);
        assertEquals(50, seat.wager(500));
        assertEquals(0, seat.credits());
        assertTrue(seat.isAllIn());
    }
}