package com.github.arrivedbog593.tablegames.engine.games.slots;

import java.util.Locale;

/**
 * What can land on a reel.
 * <p>
 * Named for the Minecraft items the screen draws them as, cheapest first, but
 * nothing here knows that: the engine only needs them to be distinct, and
 * which picture stands for which symbol is the client's business.
 */
public enum SlotSymbol {

    COAL,
    IRON,
    GOLD,
    EMERALD,
    DIAMOND,
    NETHERITE,

    /** Three in a line win a free spin at the same stake instead of credits. */
    REPLAY;

    /** Lowercase, for translation keys and the wire. */
    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
