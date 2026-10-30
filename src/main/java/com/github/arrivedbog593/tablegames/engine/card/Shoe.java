package com.github.arrivedbog593.tablegames.engine.card;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Several decks shuffled together and dealt from until the cut card comes
 * out.
 * <p>
 * Outlives the hands dealt from it, which is the whole difference from a
 * {@link Deck} built fresh for each one: what came out last hand is not in
 * the shoe this hand, and a player who keeps track can use that, exactly as
 * at a real table. A casino does not reshuffle after every hand, and neither
 * does this.
 * <p>
 * The cut card sits three quarters of the way in. Once it has been passed,
 * {@link #pastCutCard()} says so and the table reshuffles before the next
 * hand — never during one, so a hand is always dealt from a single shuffle.
 * A hand that runs the shoe dry outright, which only a single deck at a full
 * table can do, is dealt on from a fresh one rather than stopped.
 * <p>
 * Not thread-safe.
 */
public final class Shoe {

    /** How much of the shoe is dealt before the cut card. */
    private static final double PENETRATION = 0.75;

    private final int decks;
    private final RandomGenerator random;
    private final int size;
    private Deck deck;

    public Shoe(int decks, RandomGenerator random) {
        if (decks < 1) {
            throw new IllegalArgumentException("A shoe needs at least one deck: " + decks);
        }
        this.decks = decks;
        this.random = Objects.requireNonNull(random, "random");
        this.size = decks * Deck.STANDARD_SIZE;
        this.deck = Deck.shoe(decks, random);
    }

    public int decks() {
        return decks;
    }

    /** Cards in a full shoe. */
    public int size() {
        return size;
    }

    /** Cards still to be dealt. */
    public int remaining() {
        return deck.remaining();
    }

    /** Takes the next card, starting a fresh shoe if this one is empty. */
    public Card draw() {
        if (deck.isEmpty()) {
            reshuffle();
        }
        return deck.draw();
    }

    /** Whether the cut card has come out, so the next hand wants a fresh shuffle. */
    public boolean pastCutCard() {
        return deck.remaining() <= size - (int) (size * PENETRATION);
    }

    /** Every card back in, shuffled. */
    public void reshuffle() {
        deck = Deck.shoe(decks, random);
    }

    /**
     * Puts these cards on top, to be dealt next in this order.
     * <p>
     * For a test table told what to deal, and nothing else. The cards are
     * added rather than found and moved, so a stacked shoe briefly holds more
     * of them than a real one could; the hand they deal is played and paid
     * exactly as it would be had they come out on their own.
     */
    public void stack(List<Card> top) {
        List<Card> cards = new ArrayList<>(top);
        cards.addAll(deck.peekAll());
        deck = Deck.of(cards, random);
    }
}
