package com.github.arrivedbog593.tablegames.engine.card;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * A draw pile that cards are dealt from, top first.
 * <p>
 * The {@link RandomGenerator} is injected on purpose: production passes the
 * server's random, tests pass a seeded one so deals are reproducible. Never
 * use {@code Math.random()} here.
 * <p>
 * This class is NOT thread-safe. Games must be driven from the server thread.
 */
public final class Deck {

    /** Cards in one full deck, no jokers. */
    public static final int STANDARD_SIZE = Rank.values().length * Suit.values().length;

    private final Deque<Card> cards = new ArrayDeque<>();
    private final RandomGenerator random;

    private Deck(List<Card> initial, RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
        this.cards.addAll(initial);
    }

    /** A shuffled 52-card deck. */
    public static Deck standard(RandomGenerator random) {
        return shoe(1, random);
    }

    /**
     * A shuffled shoe of several decks combined.
     * Casino blackjack typically uses six to eight.
     */
    public static Deck shoe(int deckCount, RandomGenerator random) {
        if (deckCount < 1) {
            throw new IllegalArgumentException("deckCount must be >= 1, was " + deckCount);
        }
        List<Card> built = new ArrayList<>(STANDARD_SIZE * deckCount);
        for (int i = 0; i < deckCount; i++) {
            for (Suit suit : Suit.values()) {
                for (Rank rank : Rank.values()) {
                    built.add(new Card(rank, suit));
                }
            }
        }
        Deck deck = new Deck(built, random);
        deck.shuffle();
        return deck;
    }

    /** A deck holding exactly the given cards, in that order. For tests. */
    public static Deck of(List<Card> cards, RandomGenerator random) {
        return new Deck(List.copyOf(cards), random);
    }

    /** Shuffles whatever is left on the deck. */
    public void shuffle() {
        List<Card> temp = new ArrayList<>(cards);
        Collections.shuffle(temp, asJavaRandom());
        cards.clear();
        cards.addAll(temp);
    }

    /**
     * Draws the top card.
     *
     * @throws IllegalStateException if the deck is empty
     */
    public Card draw() {
        Card card = cards.pollFirst();
        if (card == null) {
            throw new IllegalStateException("Deck is empty");
        }
        return card;
    }

    /** Draws {@code count} cards. */
    public List<Card> draw(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Negative count: " + count);
        }
        if (count > cards.size()) {
            throw new IllegalStateException(
                    "Requested " + count + " cards but only " + cards.size() + " remain");
        }
        List<Card> drawn = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            drawn.add(draw());
        }
        return drawn;
    }

    /**
     * Draws a card, recycling the discard pile back into the deck if needed.
     * This is what Uno and dompe require when the deck runs out mid-hand.
     * <p>
     * The discard list is cleared as a side effect. If the game must keep the
     * face-up top card, remove it from the list before calling.
     *
     * @throws IllegalStateException if both deck and discard pile are empty
     */
    public Card drawOrRecycle(List<Card> discardPile) {
        Objects.requireNonNull(discardPile, "discardPile");
        if (cards.isEmpty()) {
            if (discardPile.isEmpty()) {
                throw new IllegalStateException("Deck and discard pile are both empty");
            }
            cards.addAll(discardPile);
            discardPile.clear();
            shuffle();
        }
        return draw();
    }

    /** Returns cards to the bottom of the deck, without shuffling. */
    public void returnToBottom(List<Card> returned) {
        cards.addAll(Objects.requireNonNull(returned, "returned"));
    }

    /** How many cards are left to draw. */
    public int remaining() {
        return cards.size();
    }

    public boolean isEmpty() {
        return cards.isEmpty();
    }

    /** Read-only view of the deck, top to bottom. For debugging and tests. */
    public List<Card> peekAll() {
        return List.copyOf(cards);
    }

    /**
     * Bridge to {@link Collections#shuffle}, which still requires a
     * {@code java.util.Random} rather than a {@code RandomGenerator}.
     */
    private java.util.Random asJavaRandom() {
        if (random instanceof java.util.Random r) {
            return r;
        }
        return new java.util.Random() {
            @Override
            public int nextInt(int bound) {
                return random.nextInt(bound);
            }

            @Override
            public long nextLong() {
                return random.nextLong();
            }
        };
    }
}