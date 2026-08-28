package com.github.arrivedbog593.tablegames.engine.card;

import java.util.Locale;

/**
 * A card's rank, from Two to Ace.
 * <p>
 * {@link #strength()} is the poker strength, where the Ace is the highest
 * card (14). The low straight A-2-3-4-5 is handled as a special case inside
 * the hand evaluator, NOT by changing this value.
 * <p>
 * Note: blackjack uses a different scoring scheme (faces = 10, Ace = 1 or
 * 11). That conversion belongs to the blackjack game, not here. This enum
 * stays neutral so it can serve every game.
 */
public enum Rank {
    TWO("2", 2),
    THREE("3", 3),
    FOUR("4", 4),
    FIVE("5", 5),
    SIX("6", 6),
    SEVEN("7", 7),
    EIGHT("8", 8),
    NINE("9", 9),
    TEN("10", 10),
    JACK("J", 11),
    QUEEN("Q", 12),
    KING("K", 13),
    ACE("A", 14);

    /** Lowest possible strength, useful for straight loops. */
    public static final int LOW_ACE_STRENGTH = 1;

    private final String code;
    private final int strength;

    Rank(String code, int strength) {
        this.code = code;
        this.strength = strength;
    }

    /** Short display symbol: "2", "10", "J", "A". */
    public String code() {
        return code;
    }

    /** Strength used to compare poker hands. Ace high = 14. */
    public int strength() {
        return strength;
    }

    /** Jack, Queen, or King. */
    public boolean isFace() {
        return this == JACK || this == QUEEN || this == KING;
    }

    /** Lowercase name, useful for building texture paths. */
    public String assetName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The next rank up, or null if this is the Ace. */
    public Rank next() {
        return this == ACE ? null : values()[ordinal() + 1];
    }
}