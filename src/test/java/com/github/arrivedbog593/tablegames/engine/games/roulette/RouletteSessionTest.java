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
                List.of(seats), fixedTo(wheel, target), wheel,
                new BetLimits(10, 10_000, 10, 10_000));
        session.begin();
        return session;
    }

    private static RouletteSession sessionWithLimits(BetLimits limits, long stack) {
        RouletteWheel wheel = RouletteWheel.EUROPEAN;
        RouletteSession session = new RouletteSession(
                List.of(Seat.forPlayer(0, ALICE, stack)),
                fixedTo(wheel, european(17)), wheel, limits);
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

    // --- The maximum is on the position, not on the chip ------------------------

    @Test
    void chipsOnTheSamePositionAddUpAgainstTheMaximum() {
        // The bug this replaced: each wager was checked alone, so five chips
        // of a thousand on one number were five legal bets that together
        // committed what one illegal bet would have. The maximum protected
        // nothing from anybody willing to click more.
        RouletteSession session = sessionLandingOn(european(17), 100_000);
        Pocket seventeen = european(17);

        for (int i = 0; i < 10; i++) {
            assertTrue(session.submit(ALICE, new RouletteAction.Place(
                            new RouletteBet(BetType.STRAIGHT_UP, seventeen, 1_000)))
                    .accepted(), "chip " + (i + 1) + " should fit under the maximum");
        }
        // Ten thousand is the maximum exactly; the eleventh chip goes over.
        assertFalse(session.submit(ALICE, new RouletteAction.Place(
                        new RouletteBet(BetType.STRAIGHT_UP, seventeen, 1)))
                .accepted());
    }

    @Test
    void differentPositionsHaveTheirOwnMaximum() {
        RouletteSession session = sessionLandingOn(european(17), 100_000);
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, european(17), 10_000))).accepted());
        // A different number is a different stake, so it starts from zero.
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, european(23), 10_000))).accepted());
        // And so is a different bet type on the same felt.
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.RED, null, 10_000))).accepted());
    }

    @Test
    void oneSeatFillingAPositionDoesNotBlockAnother() {
        // The engine's cap is per seat: it mirrors what one player may risk.
        // Protecting the bankroll from the whole table's exposure is the
        // platform's job because only it knows the bankroll.
        RouletteSession session = sessionLandingOn(european(17), 100_000, 100_000);
        Pocket seventeen = european(17);
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, seventeen, 10_000))).accepted());
        assertTrue(session.submit(BOB, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, seventeen, 10_000))).accepted());
    }

    @Test
    void clearingBetsFreesThePositionAgain() {
        RouletteSession session = sessionLandingOn(european(17), 100_000);
        Pocket seventeen = european(17);
        session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, seventeen, 10_000)));
        assertFalse(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, seventeen, 10))).accepted());

        session.submit(ALICE, new RouletteAction.ClearBets());
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, seventeen, 10_000))).accepted());
    }

    // --- The limits come from the table, not from the game ----------------------

    @Test
    void aTableMayTakeSmallerChipsThanTheDefault() {
        // The disagreement this replaced: the game fixed the minimum at ten
        // and the block enforced whatever it had been configured with, so a
        // table set to take five-credit chips accepted a wager the session
        // then refused when the bets were replayed at the spin. The player
        // watched the chip land and it was never settled either way.
        RouletteSession session = sessionWithLimits(
                new BetLimits(1, BetLimits.UNLIMITED, 1, BetLimits.UNLIMITED), 1_000);

        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                        RouletteBet.outside(BetType.RED, 5))).accepted(),
                "the table set the minimum to one, so five is a legal chip");
    }

    @Test
    void insideAndOutsideMinimumsApplyToTheirOwnBets() {
        RouletteSession session = sessionWithLimits(
                new BetLimits(100, BetLimits.UNLIMITED, 10, BetLimits.UNLIMITED), 10_000);

        assertFalse(session.submit(ALICE, new RouletteAction.Place(
                        new RouletteBet(BetType.STRAIGHT_UP, european(17), 50))).accepted(),
                "fifty is under the inside minimum");
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                        RouletteBet.outside(BetType.RED, 50))).accepted(),
                "the same fifty clears the outside minimum");
    }

    @Test
    void insideAndOutsideMaximumsApplyToTheirOwnBets() {
        // The real reason for two ceilings: a straight-up pays thirty-five to
        // one and red pays one to one, so the same stake is a very different
        // liability depending on where it sits.
        RouletteSession session = sessionWithLimits(new BetLimits(10, 100, 10, 5_000), 10_000);

        assertFalse(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, european(17), 500))).accepted());
        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                RouletteBet.outside(BetType.RED, 500))).accepted());
    }

    @Test
    void anUnconfiguredTableImposesNoCeilingOfItsOwn() {
        // What the bankroll allows is the platform's business. With no table
        // limit set, the engine must not invent one.
        RouletteSession session = sessionWithLimits(BetLimits.DEFAULT, 100_000_000);

        assertTrue(session.submit(ALICE, new RouletteAction.Place(
                new RouletteBet(BetType.STRAIGHT_UP, european(17), 50_000_000))).accepted());
    }

    @Test
    void aSessionRefusesToExistWithoutLimits() {
        assertThrows(NullPointerException.class, () -> new RouletteSession(
                List.of(Seat.forPlayer(0, ALICE, 1_000)),
                fixedTo(RouletteWheel.EUROPEAN, european(17)), RouletteWheel.EUROPEAN, null));
    }
}