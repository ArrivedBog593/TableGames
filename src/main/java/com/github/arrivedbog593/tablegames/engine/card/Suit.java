package com.github.arrivedbog593.tablegames.engine.card;

import java.util.Locale;

/**
 * The four suits of a standard English deck.
 * <p>
 * Declaration order follows the bridge standard (clubs &lt; diamonds &lt;
 * hearts &lt; spades), used by the few poker variants where suit breaks ties.
 * In Texas Hold'em and in dompe, suits are NEVER compared.
 */
public enum Suit {
    CLUBS("C", '♣', Color.BLACK),
    DIAMONDS("D", '♦', Color.RED),
    HEARTS("H", '♥', Color.RED),
    SPADES("S", '♠', Color.BLACK);

    /** Card color. Relevant for games like roulette's red/black bets. */
    public enum Color {
        RED, BLACK
    }

    private final String code;
    private final char symbol;
    private final Color color;

    Suit(String code, char symbol, Color color) {
        this.code = code;
        this.symbol = symbol;
        this.color = color;
    }

    /** Single-letter ASCII code, safe for logs and resource identifiers. */
    public String code() {
        return code;
    }

    /** Unicode suit glyph, for display purposes. */
    public char symbol() {
        return symbol;
    }

    public Color color() {
        return color;
    }

    public boolean isRed() {
        return color == Color.RED;
    }

    /** Lowercase name, useful for building texture paths. */
    public String assetName() {
        return name().toLowerCase(Locale.ROOT);
    }
}