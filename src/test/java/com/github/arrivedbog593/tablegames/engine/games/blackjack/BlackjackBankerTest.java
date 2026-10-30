package com.github.arrivedbog593.tablegames.engine.games.blackjack;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.card.Suit;
import com.github.arrivedbog593.tablegames.engine.session.Action;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hands against a player holding the bank. */
class BlackjackBankerTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final UUID BOB = UUID.nameUUIDFromBytes("bob".getBytes());
    private static final UUID BANK = UUID.nameUUIDFromBytes("bank".getBytes());

    private static final BlackjackAction HIT = new BlackjackAction.Hit();
    private static final BlackjackAction STAND = new BlackjackAction.Stand();
    private static final Action DOUBLE = new BlackjackAction.DoubleDown();

    private static Card c(Rank rank) {
        return new Card(rank, Suit.values()[0]);
    }

    /**
     * Alice alone, wagering 100 from 1000, against a bank holding this much.
     * The shoe deals Alice, bank up, Alice, bank hole, then the rest.
     */
    private static BlackjackSession aliceAgainst(BlackjackBanker bank, Rank... top) {
        Shoe shoe = new Shoe(1, new Random(3));
        List<Card> cards = new ArrayList<>();
        for (Rank rank : top) {
            cards.add(c(rank));
        }
        shoe.stack(cards);
        BlackjackSession session = new BlackjackSession(List.of(Seat.forPlayer(0, ALICE, 1000)),
                new Random(3), BlackjackRules.STANDARD, shoe, Map.of(ALICE, 100L), bank);
        session.begin();
        return session;
    }

    private static BlackjackBanker byRule(long credits) {
        return new BlackjackBanker(BANK, credits, false, false);
    }

    private static long deltaOf(BlackjackSession session, UUID playerId) {
        Outcome outcome = session.outcome().orElseThrow();
        return outcome.payouts().stream()
                .filter(payout -> payout.playerId().equals(playerId))
                .mapToLong(Payout::delta)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theBankTakesWhatTheSeatLosesAndTheHandIsZeroSum() {
        // Alice stands on 18; the bank has 20.
        BlackjackSession session = aliceAgainst(byRule(1000), Rank.TEN, Rank.TEN, Rank.EIGHT, Rank.TEN);
        session.submit(ALICE, STAND);
        session.playDealerOut();

        assertEquals(-100, deltaOf(session, ALICE));
        assertEquals(100, deltaOf(session, BANK));
        assertTrue(session.outcome().orElseThrow().isZeroSum());
        assertTrue(session.outcome().orElseThrow().winners().contains(BANK));
    }

    @Test
    void theBankPaysABlackjackAtThreeToTwo() {
        BlackjackSession session = aliceAgainst(byRule(1000), Rank.ACE, Rank.NINE, Rank.KING, Rank.SEVEN);

        session.playDealerOut();

        assertEquals(150, deltaOf(session, ALICE));
        assertEquals(-150, deltaOf(session, BANK));
    }

    @Test
    void aTieIsAPushUnlessTheBankPlaysTheHomeRule() {
        Rank[] bothTwenty = {Rank.TEN, Rank.TEN, Rank.TEN, Rank.TEN};

        BlackjackSession push = aliceAgainst(byRule(1000), bothTwenty);
        push.submit(ALICE, STAND);
        push.playDealerOut();
        assertEquals(0, deltaOf(push, ALICE));

        BlackjackSession home = aliceAgainst(new BlackjackBanker(BANK, 1000, false, true), bothTwenty);
        home.submit(ALICE, STAND);
        home.playDealerOut();
        assertEquals(-100, deltaOf(home, ALICE));
        assertEquals(100, deltaOf(home, BANK));
    }

    @Test
    void aBankThatCannotCoverTheOpeningWagersIsRefused() {
        // A blackjack at three to two would cost 150.
        assertThrows(IllegalArgumentException.class, () -> aliceAgainst(byRule(149), Rank.TEN));
    }

    @Test
    void aDoubleTheBankCouldNotPayIsNotOffered() {
        // Alice has eleven; the bank covers the 100 down but not another 100.
        BlackjackSession session = aliceAgainst(byRule(150), Rank.SIX, Rank.TEN, Rank.FIVE, Rank.SEVEN);

        assertFalse(session.legalActions(ALICE).contains(DOUBLE));
        ActionResult refused = session.submit(ALICE, DOUBLE);
        assertFalse(refused.accepted());

        BlackjackSession rich = aliceAgainst(byRule(1000), Rank.SIX, Rank.TEN, Rank.FIVE, Rank.SEVEN);
        assertTrue(rich.legalActions(ALICE).contains(DOUBLE));
    }

    @Test
    void aBankThatPlaysByHandDecidesItsOwnDraws() {
        // Alice stands on 18. The bank turns 16 and chooses to stand on it,
        // which the dealer's rule would never do.
        BlackjackSession session = aliceAgainst(new BlackjackBanker(BANK, 1000, true, false),
                Rank.TEN, Rank.TEN, Rank.EIGHT, Rank.SIX);
        session.submit(ALICE, STAND);
        session.dealerStep();

        assertTrue(session.awaitingBanker());
        assertEquals(2, session.bankerMoves().size());
        assertFalse(session.bankerAct(ALICE, STAND).accepted());
        assertTrue(session.bankerAct(BANK, STAND).accepted());
        session.dealerStep();

        assertEquals(100, deltaOf(session, ALICE));
        assertEquals(-100, deltaOf(session, BANK));
    }

    @Test
    void aBankThatPlaysByHandCanHitAsOftenAsItLikes() {
        BlackjackSession session = aliceAgainst(new BlackjackBanker(BANK, 1000, true, false),
                Rank.TEN, Rank.TEN, Rank.NINE, Rank.SEVEN, Rank.TWO, Rank.TWO);
        session.submit(ALICE, STAND);
        session.dealerStep();

        // Seventeen, and the bank hits anyway: twice, to 21.
        session.bankerAct(BANK, HIT);
        session.bankerAct(BANK, HIT);
        assertFalse(session.awaitingBanker());
        session.dealerStep();

        assertEquals(-100, deltaOf(session, ALICE));
    }

    @Test
    void aSilentBankIsDrawnForByTheDealersRule() {
        BlackjackSession session = aliceAgainst(new BlackjackBanker(BANK, 1000, true, false),
                Rank.TEN, Rank.TEN, Rank.EIGHT, Rank.SIX, Rank.FIVE);
        session.submit(ALICE, STAND);
        session.dealerStep();
        assertTrue(session.awaitingBanker());

        session.bankerTimedOut();
        session.playDealerOut();

        // Sixteen draws, to 21.
        assertEquals(-100, deltaOf(session, ALICE));
    }

    @Test
    void theBankCannotAlsoPlayASeat() {
        Shoe shoe = new Shoe(1, new Random(3));
        assertThrows(IllegalArgumentException.class, () -> new BlackjackSession(
                List.of(Seat.forPlayer(0, BANK, 1000)), new Random(3), BlackjackRules.STANDARD, shoe,
                Map.of(BANK, 100L), byRule(1000)));
    }

    @Test
    void twoSeatsAgainstOneBankSettleAgainstIt() {
        Shoe shoe = new Shoe(1, new Random(3));
        // Alice, Bob, bank up, Alice, Bob, bank hole: Alice 20, Bob 17, bank 19.
        shoe.stack(List.of(c(Rank.TEN), c(Rank.TEN), c(Rank.TEN), c(Rank.TEN), c(Rank.SEVEN), c(Rank.NINE)));
        BlackjackSession session = new BlackjackSession(
                List.of(Seat.forPlayer(0, ALICE, 1000), Seat.forPlayer(1, BOB, 1000)), new Random(3),
                BlackjackRules.STANDARD, shoe, Map.of(ALICE, 100L, BOB, 100L), byRule(1000));
        session.begin();
        session.submit(ALICE, STAND);
        session.submit(BOB, STAND);
        session.playDealerOut();

        assertEquals(100, deltaOf(session, ALICE));
        assertEquals(-100, deltaOf(session, BOB));
        assertEquals(0, deltaOf(session, BANK));
        assertTrue(session.outcome().orElseThrow().isZeroSum());
    }
}
