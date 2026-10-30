package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.card.Suit;
import com.github.arrivedbog593.tablegames.engine.session.Action;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.GameState;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlackjackSessionTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());

    private static final Action HIT = new BlackjackAction.Hit();
    private static final Action STAND = new BlackjackAction.Stand();
    private static final Action DOUBLE = new BlackjackAction.DoubleDown();
    private static final Action SPLIT = new BlackjackAction.Split();

    private static Card c(Rank rank) {
        return new Card(rank, Suit.values()[0]);
    }

    /**
     * A hand for Alice alone, wagering 100 from a stack of 1000, dealt from a
     * shoe with these cards on top in dealing order: Alice, dealer up, Alice,
     * dealer hole, then whatever is drawn.
     */
    private static BlackjackSession aliceAgainst(BlackjackRules rules, Rank... top) {
        return dealt(rules, List.of(Seat.forPlayer(0, ALICE, 1000)), Map.of(ALICE, 100L), top);
    }

    private static BlackjackSession aliceAgainst(Rank... top) {
        return aliceAgainst(BlackjackRules.STANDARD, top);
    }

    private static BlackjackSession dealt(BlackjackRules rules, List<Seat> seats,
                                          Map<UUID, Long> bets, Rank... top) {
        Shoe shoe = new Shoe(rules.decks(), new Random(7));
        List<Card> cards = new ArrayList<>();
        for (Rank rank : top) {
            cards.add(c(rank));
        }
        shoe.stack(cards);
        BlackjackSession session = new BlackjackSession(seats, new Random(7), rules, shoe, bets);
        session.begin();
        return session;
    }

    private static long deltaOf(BlackjackSession session, UUID playerId) {
        Outcome outcome = session.outcome().orElseThrow();
        return outcome.payouts().stream()
                .filter(payout -> payout.playerId().equals(playerId))
                .mapToLong(Payout::delta)
                .findFirst()
                .orElseThrow();
    }

    // --- Card arithmetic ----------------------------------------------------------------

    @Test
    void acesCountElevenUntilThatWouldBust() {
        assertEquals(21, BlackjackHand.totalOf(List.of(c(Rank.ACE), c(Rank.KING))));
        assertEquals(12, BlackjackHand.totalOf(List.of(c(Rank.ACE), c(Rank.ACE))));
        assertEquals(21, BlackjackHand.totalOf(List.of(c(Rank.ACE), c(Rank.ACE), c(Rank.NINE))));
        assertEquals(13, BlackjackHand.totalOf(List.of(c(Rank.ACE), c(Rank.FIVE), c(Rank.SEVEN))));
    }

    @Test
    void aHandIsSoftOnlyWhileAnAceStillCountsEleven() {
        assertTrue(BlackjackHand.isSoft(List.of(c(Rank.ACE), c(Rank.SIX))));
        assertFalse(BlackjackHand.isSoft(List.of(c(Rank.ACE), c(Rank.SIX), c(Rank.TEN))));
        assertFalse(BlackjackHand.isSoft(List.of(c(Rank.TEN), c(Rank.SEVEN))));
    }

    @Test
    void sixToFiveRoundsDownWithoutOverflowingNearTheCeiling() {
        assertEquals(150, NaturalPayout.THREE_TO_TWO.winOn(100));
        assertEquals(120, NaturalPayout.SIX_TO_FIVE.winOn(100));
        assertEquals(7, NaturalPayout.THREE_TO_TWO.winOn(5));
        long huge = Long.MAX_VALUE / 2;
        assertTrue(NaturalPayout.THREE_TO_TWO.winOn(huge) > huge, "no overflow into the negatives");
    }

    // --- Playing ------------------------------------------------------------------------

    @Test
    void theDealTakesTheWagerAndPutsAliceOnTheClock() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.NINE, Rank.SEVEN, Rank.EIGHT);
        assertEquals(GameState.IN_PROGRESS, session.state());
        assertEquals(BlackjackStage.PLAYERS, session.stage());
        assertEquals(ALICE, session.currentTurn().orElseThrow());
        assertEquals(900, session.seats().getFirst().credits());
        assertEquals(1, session.dealerShown().size(), "the hole card stays hidden");
        assertTrue(session.holeHidden());
    }

    @Test
    void standingOnSeventeenAgainstEighteenLoses() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.NINE, Rank.SEVEN, Rank.NINE);
        session.submit(ALICE, STAND);
        assertEquals(BlackjackStage.DEALER, session.stage());
        session.playDealerOut();
        assertEquals(-100, deltaOf(session, ALICE));
        assertEquals(2, session.dealerShown().size());
    }

    @Test
    void bustingLosesWithoutTheDealerDrawing() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.SIX, Rank.SIX, Rank.TEN, Rank.KING);
        session.submit(ALICE, HIT);
        assertTrue(session.handsOf(ALICE).getFirst().busted());
        session.playDealerOut();
        assertEquals(-100, deltaOf(session, ALICE));
        assertEquals(2, session.dealerShown().size(), "nothing left to beat, so no third card");
    }

    @Test
    void theDealerDrawsToSeventeenAndCanBust() {
        BlackjackSession session = aliceAgainst(
                Rank.TEN, Rank.SIX, Rank.EIGHT, Rank.TEN, Rank.TEN);
        session.submit(ALICE, STAND);
        session.playDealerOut();
        assertEquals(26, BlackjackHand.totalOf(session.dealerShown()));
        assertEquals(100, deltaOf(session, ALICE));
    }

    @Test
    void aPushReturnsTheWager() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.TEN, Rank.EIGHT, Rank.EIGHT);
        session.submit(ALICE, STAND);
        session.playDealerOut();
        assertEquals(0, deltaOf(session, ALICE));
    }

    @Test
    void blackjackPaysThreeToTwoWithoutWaitingForATurn() {
        BlackjackSession session = aliceAgainst(Rank.ACE, Rank.NINE, Rank.KING, Rank.SEVEN);
        assertEquals(BlackjackStage.DEALER, session.stage(), "nothing to decide on a blackjack");
        session.playDealerOut();
        assertEquals(150, deltaOf(session, ALICE));
    }

    @Test
    void sixToFiveTablesPayLessForTheSameBlackjack() {
        BlackjackSession session = aliceAgainst(new BlackjackRules(1, false, NaturalPayout.SIX_TO_FIVE),
                Rank.ACE, Rank.NINE, Rank.KING, Rank.SEVEN);
        session.playDealerOut();
        assertEquals(120, deltaOf(session, ALICE));
    }

    @Test
    void doublingMatchesTheWagerAndTakesExactlyOneCard() {
        BlackjackSession session = aliceAgainst(
                Rank.SIX, Rank.SIX, Rank.FIVE, Rank.TEN, Rank.TEN, Rank.TEN);
        assertTrue(session.legalActions(ALICE).contains(DOUBLE));
        session.submit(ALICE, DOUBLE);
        BlackjackHand hand = session.handsOf(ALICE).getFirst();
        assertEquals(200, hand.bet());
        assertEquals(3, hand.cards().size());
        assertTrue(hand.finished());
        session.playDealerOut();
        assertEquals(200, deltaOf(session, ALICE));
    }

    @Test
    void doublingIsRefusedWhenTheStackCannotMatch() {
        BlackjackSession session = dealt(BlackjackRules.STANDARD,
                List.of(Seat.forPlayer(0, ALICE, 150)), Map.of(ALICE, 100L),
                Rank.SIX, Rank.SIX, Rank.FIVE, Rank.TEN);
        assertFalse(session.legalActions(ALICE).contains(DOUBLE));
        ActionResult result = session.submit(ALICE, DOUBLE);
        assertFalse(result.accepted());
    }

    @Test
    void splittingPlaysEachHandInTurnWithItsOwnWager() {
        // Alice 8 8 against a six; the first eight draws a three and a ten,
        // the second a ten. The dealer turns a ten and busts on another.
        BlackjackSession session = aliceAgainst(
                Rank.EIGHT, Rank.SIX, Rank.EIGHT, Rank.TEN,
                Rank.THREE, Rank.TEN, Rank.TEN, Rank.TEN);
        assertTrue(session.legalActions(ALICE).contains(SPLIT));
        session.submit(ALICE, SPLIT);
        assertEquals(2, session.handsOf(ALICE).size());
        assertEquals(800, session.seats().getFirst().credits());
        assertEquals(11, session.handsOf(ALICE).get(0).total());

        session.submit(ALICE, HIT);
        assertEquals(21, session.handsOf(ALICE).get(0).total());
        assertEquals(1, session.handOnClockIndex(ALICE), "twenty-one stands and play moves on");
        assertEquals(18, session.handsOf(ALICE).get(1).total());

        session.submit(ALICE, STAND);
        session.playDealerOut();
        assertEquals(200, deltaOf(session, ALICE));
    }

    @Test
    void twentyOneAfterASplitIsNotABlackjack() {
        BlackjackSession session = aliceAgainst(
                Rank.ACE, Rank.NINE, Rank.ACE, Rank.SEVEN, Rank.KING, Rank.KING, Rank.TEN);
        session.submit(ALICE, SPLIT);
        List<BlackjackHand> hands = session.handsOf(ALICE);
        assertTrue(hands.get(0).finished() && hands.get(1).finished(), "split aces take one card");
        assertFalse(hands.get(0).natural());
        assertEquals(BlackjackStage.DEALER, session.stage());
        session.playDealerOut();
        assertEquals(200, deltaOf(session, ALICE), "two even-money wins, not two blackjacks");
    }

    @Test
    void noSeatPlaysMoreThanFourHands() {
        BlackjackSession session = dealt(BlackjackRules.STANDARD,
                List.of(Seat.forPlayer(0, ALICE, 10_000)), Map.of(ALICE, 100L),
                Rank.EIGHT, Rank.SIX, Rank.EIGHT, Rank.TEN,
                Rank.EIGHT, Rank.EIGHT, Rank.EIGHT, Rank.EIGHT);
        for (int i = 0; i < 3; i++) {
            assertTrue(session.submit(ALICE, SPLIT).accepted());
        }
        assertEquals(4, session.handsOf(ALICE).size());
        assertFalse(session.legalActions(ALICE).contains(SPLIT));
        assertFalse(session.submit(ALICE, SPLIT).accepted());
    }

    @Test
    void seatsActInOrderAndOnlyOnTheirTurn() {
        Map<UUID, Long> bets = new LinkedHashMap<>();
        bets.put(ALICE, 100L);
        bets.put(BOB, 100L);
        BlackjackSession session = dealt(BlackjackRules.STANDARD,
                List.of(Seat.forPlayer(0, ALICE, 1000), Seat.forPlayer(1, BOB, 1000)), bets,
                Rank.TEN, Rank.TEN, Rank.TEN, Rank.TEN, Rank.SEVEN, Rank.TEN);
        assertEquals(ALICE, session.currentTurn().orElseThrow());
        assertFalse(session.submit(BOB, STAND).accepted());
        session.submit(ALICE, STAND);
        assertEquals(BOB, session.currentTurn().orElseThrow());
        session.submit(BOB, STAND);
        session.playDealerOut();
        assertEquals(0, deltaOf(session, ALICE), "twenty against twenty");
        assertEquals(-100, deltaOf(session, BOB), "seventeen against twenty");
    }

    @Test
    void runningOutTheClockStands() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.NINE, Rank.NINE, Rank.EIGHT);
        session.timeOutCurrentTurn();
        assertTrue(session.handsOf(ALICE).getFirst().finished());
        assertEquals(BlackjackStage.DEALER, session.stage());
    }

    // --- The dealer's rules ---------------------------------------------------------------

    @Test
    void aSoftSeventeenStandsUnlessTheTableSaysOtherwise() {
        Rank[] cards = {Rank.TEN, Rank.SIX, Rank.EIGHT, Rank.ACE, Rank.TWO};
        BlackjackSession stands = aliceAgainst(cards);
        stands.submit(ALICE, STAND);
        stands.playDealerOut();
        assertEquals(17, BlackjackHand.totalOf(stands.dealerShown()));

        BlackjackSession hits = aliceAgainst(new BlackjackRules(6, true, NaturalPayout.THREE_TO_TWO), cards);
        hits.submit(ALICE, STAND);
        hits.playDealerOut();
        assertEquals(19, BlackjackHand.totalOf(hits.dealerShown()));
    }

    // --- Insurance and the peek -------------------------------------------------------------

    @Test
    void anAceOffersInsuranceToEverybodyAtOnce() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.ACE, Rank.NINE, Rank.SEVEN);
        assertEquals(BlackjackStage.INSURANCE, session.stage());
        assertTrue(session.currentTurn().isEmpty());
        assertTrue(session.submit(ALICE, new BlackjackAction.Insurance(true)).accepted());
        assertEquals(50, session.insuranceOf(ALICE));
        assertTrue(session.allInsuranceAnswered());
        session.closeInsurance();
        assertEquals(BlackjackStage.PLAYERS, session.stage());
        session.submit(ALICE, STAND);
        session.playDealerOut();
        assertEquals(100 - 50, deltaOf(session, ALICE), "nineteen beats eighteen; insurance lost");
    }

    @Test
    void insurancePaysTwoToOneAgainstADealerBlackjack() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.ACE, Rank.NINE, Rank.KING);
        session.submit(ALICE, new BlackjackAction.Insurance(true));
        session.closeInsurance();
        assertEquals(BlackjackStage.SETTLED, session.stage(), "the peek ends the hand");
        assertEquals(0, deltaOf(session, ALICE), "the wager lost, the insurance won twice that half");
    }

    @Test
    void aDealerBlackjackUnderATenEndsTheHandBeforeAnybodyMoves() {
        BlackjackSession session = aliceAgainst(Rank.TEN, Rank.KING, Rank.NINE, Rank.ACE);
        assertEquals(BlackjackStage.SETTLED, session.stage());
        assertEquals(-100, deltaOf(session, ALICE));
        assertFalse(session.holeHidden());
    }

    @Test
    void blackjackAgainstBlackjackPushes() {
        BlackjackSession session = aliceAgainst(Rank.ACE, Rank.ACE, Rank.KING, Rank.KING);
        session.closeInsurance();
        assertEquals(0, deltaOf(session, ALICE));
    }

    // --- What the house stands to lose ---------------------------------------------------------

    @Test
    void worstCaseCountsEveryLiveWagerAndDropsWhenSettled() {
        BlackjackSession session = aliceAgainst(
                Rank.EIGHT, Rank.SIX, Rank.EIGHT, Rank.TEN, Rank.THREE, Rank.TEN, Rank.TEN, Rank.TEN);
        assertEquals(100, session.worstCaseHouseCost());
        assertEquals(100, session.nextWagerOnClock());
        session.submit(ALICE, SPLIT);
        assertEquals(200, session.worstCaseHouseCost());
        assertEquals(200, session.wageredBy(ALICE));
        session.submit(ALICE, HIT);
        session.submit(ALICE, STAND);
        session.playDealerOut();
        assertEquals(0, session.worstCaseHouseCost());
    }

    @Test
    void theHouseNeverPaysMoreThanItsWorstCase() {
        // Every rigged situation, at every seat count, settles within the
        // worst case the hand quoted as it was dealt plus what was put down
        // after it.
        for (BlackjackRig rig : BlackjackRig.values()) {
            for (int players = 1; players <= 3; players++) {
                List<Seat> seats = new ArrayList<>();
                Map<UUID, Long> bets = new LinkedHashMap<>();
                for (int i = 0; i < players; i++) {
                    UUID id = UUID.nameUUIDFromBytes(("p" + i).getBytes());
                    seats.add(Seat.forPlayer(i, id, 10_000));
                    bets.put(id, 100L);
                }
                Shoe shoe = new Shoe(6, new Random(players));
                shoe.stack(rig.cardsFor(players));
                BlackjackSession session = new BlackjackSession(
                        seats, new Random(1), BlackjackRules.STANDARD, shoe, bets);
                session.begin();
                session.closeInsurance();
                long worst = session.worstCaseHouseCost();
                while (session.stage() == BlackjackStage.PLAYERS) {
                    UUID up = session.currentTurn().orElseThrow();
                    List<Action> legal = session.legalActions(up);
                    Action move = legal.contains(SPLIT) ? SPLIT : legal.contains(DOUBLE) ? DOUBLE : STAND;
                    worst += move == STAND ? 0 : session.nextWagerOnClock();
                    session.submit(up, move);
                }
                session.playDealerOut();
                long housePaid = session.outcome().orElseThrow().netCreditChange();
                assertTrue(housePaid <= worst, rig + " with " + players + " paid " + housePaid
                        + " against a worst case of " + worst);
            }
        }
    }

    @Test
    void everyRiggedHandIsTheSituationItIsNamedAfter() {
        assertEquals(150, deltaOf(played(BlackjackRig.PLAYER_BLACKJACK), ALICE));
        assertEquals(-100, deltaOf(played(BlackjackRig.DEALER_BLACKJACK), ALICE));
        assertEquals(BlackjackStage.INSURANCE, dealtOnly(BlackjackRig.INSURANCE_LOST).stage());
        assertTrue(dealtOnly(BlackjackRig.PAIR).legalActions(ALICE).contains(SPLIT));
        assertTrue(dealtOnly(BlackjackRig.DOUBLE).legalActions(ALICE).contains(DOUBLE));
        BlackjackSession busts = dealtOnly(BlackjackRig.DEALER_BUSTS);
        busts.submit(ALICE, STAND);
        busts.playDealerOut();
        assertTrue(BlackjackHand.totalOf(busts.dealerShown()) > 21);
    }

    private static BlackjackSession dealtOnly(BlackjackRig rig) {
        Shoe shoe = new Shoe(6, new Random(3));
        shoe.stack(rig.cardsFor(1));
        BlackjackSession session = new BlackjackSession(List.of(Seat.forPlayer(0, ALICE, 1000)),
                new Random(3), BlackjackRules.STANDARD, shoe, Map.of(ALICE, 100L));
        session.begin();
        return session;
    }

    private static BlackjackSession played(BlackjackRig rig) {
        BlackjackSession session = dealtOnly(rig);
        session.closeInsurance();
        while (session.stage() == BlackjackStage.PLAYERS) {
            session.submit(session.currentTurn().orElseThrow(), STAND);
        }
        session.playDealerOut();
        return session;
    }
}
