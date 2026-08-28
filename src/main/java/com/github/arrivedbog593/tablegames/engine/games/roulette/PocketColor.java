package com.github.arrivedbog593.tablegames.engine.games.roulette;

import java.util.Locale;

/**
 * The color of a wheel pocket.
 * <p>
 * Distinct from {@code Suit.Color} because a wheel needs a third value:
 * the zeros are green and belong to neither side of a red/black bet.
 */
public enum PocketColor {
    RED,
    BLACK,
    GREEN;

    public String translationKey() {
        return "tablegames.roulette.color." + name().toLowerCase(Locale.ROOT);
    }
}