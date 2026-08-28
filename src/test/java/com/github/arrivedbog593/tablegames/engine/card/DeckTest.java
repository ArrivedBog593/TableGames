package com.github.arrivedbog593.tablegames.engine.card;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * If these pass, the test source set is wired correctly and the engine
 * package has no Minecraft dependency. They run in milliseconds.
 */
class DeckTest {

    private static Random seeded() {
        return new Random(1234L);
    }

    @Test
    void standardDeckHas52UniqueCards() {
        Deck deck = Deck.standard(seeded());
        assertEquals(52, deck.remaining());

        Set<Card> unique = new HashSet<>(deck.peekAll());
        assertEquals(52, unique.size(), "duplicate cards in a single deck");
    }

    @Test
    void sixDeckShoeHas312Cards() {
        Deck deck = Deck.shoe(6, seeded());
        assertEquals(312, deck.remaining());
    }

    @Test
    void drawingEmptiesTheDeckExactly() {
        Deck deck = Deck.standard(seeded());
        List<Card> all = new ArrayList<>();
        while (!deck.isEmpty()) {
            all.add(deck.draw());
        }
        assertEquals(52, all.size());
        assertThrows(IllegalStateException.class, deck::draw);
    }

    @Test
    void sameSeedProducesSameDeal() {
        assertEquals(
                Deck.standard(new Random(99L)).peekAll(),
                Deck.standard(new Random(99L)).peekAll());
    }

    @Test
    void differentSeedsProduceDifferentDeals() {
        assertNotEquals(
                Deck.standard(new Random(1L)).peekAll(),
                Deck.standard(new Random(2L)).peekAll());
    }

    @Test
    void drawOrRecycleReusesTheDiscardPile() {
        Deck deck = Deck.of(List.of(Card.of(Rank.ACE, Suit.SPADES)), seeded());
        List<Card> discard = new ArrayList<>(List.of(
                Card.of(Rank.TWO, Suit.HEARTS),
                Card.of(Rank.THREE, Suit.CLUBS)));

        deck.drawOrRecycle(discard); // consumes the deck's only card
        Card recycled = deck.drawOrRecycle(discard);

        assertTrue(discard.isEmpty(), "discard pile should have been emptied");
        assertEquals(1, deck.remaining());
        assertTrue(recycled.equals(Card.of(Rank.TWO, Suit.HEARTS))
                || recycled.equals(Card.of(Rank.THREE, Suit.CLUBS)));
    }

    @Test
    void cardCodeIsReadable() {
        assertEquals("AS", Card.of(Rank.ACE, Suit.SPADES).code());
        assertEquals("10H", Card.of(Rank.TEN, Suit.HEARTS).code());
        assertEquals("ace_of_spades", Card.of(Rank.ACE, Suit.SPADES).assetName());
    }
}