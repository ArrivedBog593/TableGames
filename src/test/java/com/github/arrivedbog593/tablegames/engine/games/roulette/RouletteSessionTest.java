package com.github.arrivedbog593.tablegames.engine.games.roulette;

import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.GameState;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouletteSessionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    /** A wheel stand-in that always lands on a chosen pocket. */
    private static RandomGenerator fixedTo(RouletteWheel wheel, Pocket target) {
        int index = wheel.pockets().indexOf(target);
        if (index < 0) {
            throw new IllegalArgumentException("Pocket not on this wheel: " + target);
        }
        return new Random() {
            @Override
            public int nextInt(int bound) {
                return index;
            }
        };
    }

    private static RouletteSession sessionLandingOn(Pocket target, long... stacks) {
        RouletteWheel wheel = RouletteWheel.EUROPEAN;
        Seat[] seats = new Seat[stacks.length];
        UUID[] ids = {ALICE, BOB};
        for (int i = 0; i < stacks.length; i++) {
            seats[i] = Seat.forPlayer(i, ids[i], stacks[i]);
        }
        RouletteSession session = new RouletteSession(
                List.of(seats), fixedTo(wheel, target), wheel, 10, 10_000);
        session.begin();
        return session;
    }

    private static Pocket european(int number) {
        return RouletteWheel.EUROPEAN.pockets().stream()
                .filter(p -> p.number() == number && !p.doubleZero())
                .findFirst()
                .orElseThrow();
    }

    @Test
    void beginOpensBettingWithNobodyOnTheClock() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        assertEquals(GameState.BETTING, session.state());
        assertTrue(session.currentTurn().isEmpty(), "roulette is turn-less");
    }

    @Test
    void placingABetDeductsCreditsImmediately() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        ActionResult result = session.submit(ALICE,
                new RouletteAction.Place(RouletteBet.outside(BetType.BLACK, 200)));

        assertTrue(result.accepted());
        assertEquals(800, session.seats().getFirst().credits());
        assertEquals(1, session.betsOf(ALICE).size());
    }

    @Test
    void aPlayerMayHoldSeveralBetsAtOnce() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.BLACK, 100)));
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.straightUp(european(17), 50)));
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.DOZEN_SECOND, 25)));

        assertEquals(3, session.betsOf(ALICE).size());
        assertEquals(825, session.seats().getFirst().credits());
    }

    @Test
    void bettingMoreThanTheStackIsRejected() {
        RouletteSession session = sessionLandingOn(european(17), 100);
        ActionResult result = session.submit(ALICE,
                new RouletteAction.Place(RouletteBet.outside(BetType.RED, 500)));

        assertFalse(result.accepted());
        assertEquals("tablegames.reject.insufficient_credits", result.messageKey());
        assertEquals(100, session.seats().getFirst().credits());
    }

    @Test
    void betsBelowMinimumOrAboveMaximumAreRejected() {
        RouletteSession session = sessionLandingOn(european(17), 100_000);
        assertEquals("tablegames.reject.below_minimum_bet", session.submit(ALICE,
                        new RouletteAction.Place(RouletteBet.outside(BetType.RED, 5)))
                .messageKey());
        assertEquals("tablegames.reject.above_maximum_bet", session.submit(ALICE,
                        new RouletteAction.Place(RouletteBet.outside(BetType.RED, 50_000)))
                .messageKey());
    }

    @Test
    void doubleZeroCannotBeBetOnAEuropeanWheel() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        ActionResult result = session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.straightUp(Pocket.doubleZeroPocket(), 100)));

        assertEquals("tablegames.reject.no_such_pocket", result.messageKey());
    }

    @Test
    void clearingBetsRefundsEveryChip() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.RED, 300)));
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.HIGH, 200)));

        assertTrue(session.submit(ALICE, new RouletteAction.ClearBets()).accepted());
        assertEquals(1000, session.seats().getFirst().credits());
        assertTrue(session.betsOf(ALICE).isEmpty());
    }

    @Test
    void evenMoneyWinReturnsStakePlusEqualProfit() {
        // 17 is black.
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.BLACK, 200)));
        session.spin();

        assertEquals(1200, session.seats().getFirst().credits());
        assertEquals(200, session.outcome().orElseThrow().payouts().getFirst().delta());
    }

    @Test
    void straightUpWinPaysThirtyFiveToOne() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.straightUp(european(17), 100)));
        session.spin();

        // 100 staked returns 3600: the stake plus 35 times the stake.
        assertEquals(4500, session.seats().getFirst().credits());
        assertEquals(3500, session.outcome().orElseThrow().payouts().getFirst().delta());
    }

    @Test
    void zeroSweepsEveryOutsideBet() {
        RouletteSession session = sessionLandingOn(european(0), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.RED, 100)));
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.EVEN, 100)));
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.LOW, 100)));
        session.spin();

        assertEquals(700, session.seats().getFirst().credits());
        assertEquals(-300, session.outcome().orElseThrow().payouts().getFirst().delta());
    }

    @Test
    void aStraightUpOnZeroStillWins() {
        RouletteSession session = sessionLandingOn(european(0), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.straightUp(european(0), 100)));
        session.spin();

        assertEquals(4500, session.seats().getFirst().credits());
    }

    @Test
    void mixedBetsSettleIndependently() {
        // Lands on 17: black, odd, second dozen, second column.
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.BLACK, 100)));   // wins, +100
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.EVEN, 100)));    // loses, -100
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.DOZEN_SECOND, 100))); // wins, +200
        session.spin();

        assertEquals(1200, session.seats().getFirst().credits());
        assertEquals(200, session.outcome().orElseThrow().payouts().getFirst().delta());
    }

    @Test
    void houseBankedOutcomeIsNotZeroSum() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.straightUp(european(17), 100)));
        session.spin();

        Outcome outcome = session.outcome().orElseThrow();
        assertFalse(outcome.isZeroSum(),
                "the house pays a big win out of its own balance");
        assertEquals(3500, outcome.netCreditChange());
    }

    @Test
    void severalPlayersAreSettledSeparately() {
        RouletteSession session = sessionLandingOn(european(17), 1000, 1000);
        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.BLACK, 200)));
        session.submit(BOB, new RouletteAction.Place(
                RouletteBet.outside(BetType.RED, 200)));
        session.spin();

        assertEquals(1200, session.seats().get(0).credits());
        assertEquals(800, session.seats().get(1).credits());
        assertEquals(List.of(ALICE), session.outcome().orElseThrow().winners());
    }

    @Test
    void doneBettingIsTrackedPerPlayer() {
        RouletteSession session = sessionLandingOn(european(17), 1000, 1000);
        assertFalse(session.allDoneBetting());

        session.submit(ALICE, new RouletteAction.Done());
        assertFalse(session.allDoneBetting());

        session.submit(BOB, new RouletteAction.Done());
        assertTrue(session.allDoneBetting());
    }

    @Test
    void placingANewBetUndoesTheDoneFlag() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.submit(ALICE, new RouletteAction.Done());
        assertTrue(session.allDoneBetting());

        session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.RED, 100)));
        assertFalse(session.allDoneBetting());
    }

    @Test
    void noBetsAcceptedAfterTheSpin() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.spin();

        assertEquals(GameState.FINISHED, session.state());
        assertEquals("tablegames.reject.wrong_state", session.submit(ALICE,
                        new RouletteAction.Place(RouletteBet.outside(BetType.RED, 100)))
                .messageKey());
    }

    @Test
    void spinningTwiceIsRefused() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        session.spin();
        assertThrows(IllegalStateException.class, session::spin);
    }

    @Test
    void resultIsOnlyAvailableAfterTheSpin() {
        RouletteSession session = sessionLandingOn(european(17), 1000);
        assertTrue(session.result().isEmpty());
        session.spin();
        assertEquals(european(17), session.result().orElseThrow());
    }
}