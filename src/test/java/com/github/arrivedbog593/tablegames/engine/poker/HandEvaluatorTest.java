package com.github.arrivedbog593.tablegames.engine.poker;

import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Suit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandEvaluatorTest {

    /** Shorthand: "AS 10H 2C" -> list of cards. */
    private static List<Card> hand(String notation) {
        List<Card> cards = new ArrayList<>();
        for (String token : notation.trim().split("\\s+")) {
            String rankCode = token.substring(0, token.length() - 1);
            String suitCode = token.substring(token.length() - 1);
            Rank rank = null;
            for (Rank r : Rank.values()) {
                if (r.code().equals(rankCode)) {
                    rank = r;
                    break;
                }
            }
            Suit suit = null;
            for (Suit s : Suit.values()) {
                if (s.code().equals(suitCode)) {
                    suit = s;
                    break;
                }
            }
            if (rank == null || suit == null) {
                throw new IllegalArgumentException("Invalid card: " + token);
            }
            cards.add(new Card(rank, suit));
        }
        return cards;
    }

    private static HandCategory categoryOf(String notation) {
        return HandEvaluator.evaluate(hand(notation)).category();
    }

    // --- Category recognition -------------------------------------------

    @Test
    void recognizesEveryCategory() {
        assertEquals(HandCategory.STRAIGHT_FLUSH, categoryOf("AS KS QS JS 10S"));
        assertEquals(HandCategory.STRAIGHT_FLUSH, categoryOf("9H 8H 7H 6H 5H"));
        assertEquals(HandCategory.FOUR_OF_A_KIND, categoryOf("7S 7H 7D 7C 2S"));
        assertEquals(HandCategory.FULL_HOUSE, categoryOf("KS KH KD 4C 4S"));
        assertEquals(HandCategory.FLUSH, categoryOf("AD JD 9D 6D 3D"));
        assertEquals(HandCategory.STRAIGHT, categoryOf("9S 8H 7D 6C 5S"));
        assertEquals(HandCategory.THREE_OF_A_KIND, categoryOf("QS QH QD 8C 3S"));
        assertEquals(HandCategory.TWO_PAIR, categoryOf("JS JH 5D 5C 9S"));
        assertEquals(HandCategory.PAIR, categoryOf("10S 10H 8D 6C 3S"));
        assertEquals(HandCategory.HIGH_CARD, categoryOf("AS JH 9D 6C 3S"));
    }

    // --- The A-2-3-4-5 wheel ---------------------------------------------

    @Test
    void wheelIsAStraightWithFiveHigh() {
        HandRank wheel = HandEvaluator.evaluate(hand("AS 5H 4D 3C 2S"));
        assertEquals(HandCategory.STRAIGHT, wheel.category());
        assertEquals(List.of(5), wheel.tiebreakers());
    }

    @Test
    void wheelLosesToSixHighStraight() {
        HandRank wheel = HandEvaluator.evaluate(hand("AS 5H 4D 3C 2S"));
        HandRank six = HandEvaluator.evaluate(hand("6S 5D 4C 3H 2D"));
        assertTrue(six.beats(wheel), "2-6 should beat the wheel");
    }

    @Test
    void aceDoesNotWrapAroundToTwo() {
        // K-A-2-3-4 is NOT a straight
        assertEquals(HandCategory.HIGH_CARD, categoryOf("KS AH 2D 3C 4S"));
    }

    // --- Category hierarchy ----------------------------------------------

    @Test
    void fullHierarchyInOrder() {
        List<String> ascending = List.of(
                "AS JH 9D 6C 3S",   // high card
                "10S 10H 8D 6C 3S", // pair
                "JS JH 5D 5C 9S",   // two pairs
                "QS QH QD 8C 3S",   // three of a kind
                "9S 8H 7D 6C 5S",   // straight
                "AD JD 9D 6D 3D",   // flush
                "KS KH KD 4C 4S",   // full house
                "7S 7H 7D 7C 2S",   // four of a kind
                "AS KS QS JS 10S"); // straight flush

        for (int i = 1; i < ascending.size(); i++) {
            HandRank lower = HandEvaluator.evaluate(hand(ascending.get(i - 1)));
            HandRank higher = HandEvaluator.evaluate(hand(ascending.get(i)));
            assertTrue(higher.beats(lower),
                    higher.category() + " should beat " + lower.category());
        }
    }

    // --- Tiebreaking within a category -----------------------------------

    @Test
    void twoPairBreaksByHighPairThenKicker() {
        HandRank highPair = HandEvaluator.evaluate(hand("KS KH 3D 3C 5S"));
        HandRank lowPair = HandEvaluator.evaluate(hand("QS QH JD JC 5S"));
        assertTrue(highPair.beats(lowPair), "KK+33 beats QQ+JJ on the higher pair");

        HandRank highKicker = HandEvaluator.evaluate(hand("KS KH 3D 3C AS"));
        HandRank lowKicker = HandEvaluator.evaluate(hand("KD KC 3H 3S 5D"));
        assertTrue(highKicker.beats(lowKicker), "same two pair, kicker decides");
    }

    @Test
    void flushBreaksCardByCard() {
        HandRank higher = HandEvaluator.evaluate(hand("AD QD 9D 6D 3D"));
        HandRank lower = HandEvaluator.evaluate(hand("AS JS 9S 6S 3S"));
        assertTrue(higher.beats(lower), "Q outranks J on the second card");
    }

    @Test
    void identicalHandsOfDifferentSuitsTie() {
        HandRank a = HandEvaluator.evaluate(hand("AS KH QD JC 9S"));
        HandRank b = HandEvaluator.evaluate(hand("AD KS QC JH 9D"));
        assertTrue(a.ties(b), "suits never break ties in poker");
    }

    // --- Royal flush ------------------------------------------------------

    @Test
    void royalFlushIsDetectedButIsNotItsOwnCategory() {
        HandRank royal = HandEvaluator.evaluate(hand("AS KS QS JS 10S"));
        HandRank plain = HandEvaluator.evaluate(hand("9H 8H 7H 6H 5H"));

        assertTrue(royal.isRoyalFlush());
        assertFalse(plain.isRoyalFlush());
        assertEquals(HandCategory.STRAIGHT_FLUSH, royal.category());
        assertTrue(royal.beats(plain));
        assertEquals("tablegames.hand.royal_flush", royal.translationKey());
        assertEquals("tablegames.hand.straight_flush", plain.translationKey());
    }

    // --- Best of N --------------------------------------------------------

    @Test
    void bestFindsTheStrongestHandOfSeven() {
        HandEvaluator.BestHand best = HandEvaluator.best(hand("KS KH KD KC 9S 9H 2D"));
        assertEquals(HandCategory.FOUR_OF_A_KIND, best.rank().category());
        assertEquals(5, best.cards().size());
    }

    @Test
    void bestOfSixCardsIsTheDompeCase() {
        // Six cards holding trips plus a pair: the best five is a full house.
        HandEvaluator.BestHand best = HandEvaluator.best(hand("8S 8H 8D 4C 4S 2H"));
        assertEquals(HandCategory.FULL_HOUSE, best.rank().category());
    }

    @Test
    void bestOfFiveCardsMatchesEvaluate() {
        List<Card> five = hand("QS QH QD 8C 3S");
        assertEquals(HandEvaluator.evaluate(five), HandEvaluator.bestRank(five));
    }

    // --- Validation -------------------------------------------------------

    @Test
    void rejectsWrongHandSizes() {
        assertThrows(IllegalArgumentException.class,
                () -> HandEvaluator.evaluate(hand("AS KH QD")));
        assertThrows(IllegalArgumentException.class,
                () -> HandEvaluator.best(hand("AS KH QD JC")));
    }
}