package com.github.arrivedbog593.tablegames.engine.poker;

import java.util.Locale;

/**
 * Poker hand categories, declared from weakest to strongest.
 * <p>
 * The {@code ordinal()} IS the category strength, so declaration order
 * matters and must not be reordered.
 * <p>
 * There is deliberately no separate ROYAL_FLUSH constant: a royal flush is
 * just a {@link #STRAIGHT_FLUSH} with an Ace high, and normal tiebreaking
 * already ranks it above every other straight flush. See
 * {@link HandRank#isRoyalFlush()} for naming it in the UI.
 * <p>
 * This enum knows no display text, only translation keys. The engine package
 * must not import {@code Component}: it stays pure Java so it can be tested
 * without launching Minecraft. Resolving keys to text belongs to the
 * platform layer.
 */
public enum HandCategory {
    HIGH_CARD,
    PAIR,
    TWO_PAIR,
    THREE_OF_A_KIND,
    STRAIGHT,
    FLUSH,
    FULL_HOUSE,
    FOUR_OF_A_KIND,
    STRAIGHT_FLUSH;

    private static final String KEY_PREFIX = "tablegames.hand.";

    private final String translationKey = KEY_PREFIX + name().toLowerCase(Locale.ROOT);

    /** Translation key, e.g. {@code "tablegames.hand.two_pair"}. */
    public String translationKey() {
        return translationKey;
    }
}