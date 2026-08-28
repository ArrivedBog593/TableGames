package com.github.arrivedbog593.tablegames.engine.card;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A card from a standard English deck. Immutable and compared by value.
 * <p>
 * Being a record, {@code equals} and {@code hashCode} come for free and are
 * correct, so cards can be dropped into Sets and Maps safely.
 * <p>
 * Note: Uno does NOT use this class. Its deck has colors and actions instead
 * of suits and ranks, so it gets its own model under
 * {@code engine.games.uno}. Forcing both into one type would only produce
 * null fields everywhere.
 */
public record Card(Rank rank, Suit suit) {

    public Card {
        Objects.requireNonNull(rank, "rank");
        Objects.requireNonNull(suit, "suit");
    }

    public static Card of(Rank rank, Suit suit) {
        return new Card(rank, suit);
    }

    /** Rank strength, shorthand for {@code rank().strength()}. */
    public int strength() {
        return rank.strength();
    }

    /** Compact ASCII code, e.g. "AS", "10H". For logs and persistence. */
    public String code() {
        return rank.code() + suit.code();
    }

    /** Display form using the Unicode suit glyph. */
    public String display() {
        return rank.code() + suit.symbol();
    }

    /**
     * Namespace-less resource id, e.g. "ace_of_spades".
     * Useful for mapping to generated textures.
     */
    public String assetName() {
        return rank.assetName() + "_of_" + suit.assetName();
    }

    @Override
    public @NotNull String toString() {
        return code();
    }
}