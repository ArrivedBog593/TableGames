package com.github.arrivedbog593.tablegames.platform.block;

import net.minecraft.util.StringRepresentable;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * How a table looks once a game has been assigned to it.
 * <p>
 * Lives in the platform layer, not the engine: this is purely a rendering
 * concern, and {@link net.minecraft.world.level.block.state.properties.EnumProperty}
 * requires {@link StringRepresentable}, which the engine must never import.
 * No game rule depends on how a table is painted.
 * <p>
 * Block state properties must be a fixed, finite set known at registration
 * time, so this cannot grow with the game registry. It does not need to:
 * several games share a look. Poker, dompe and blackjack are all
 * {@link #CARDS}; only a genuinely new kind of table needs a new constant.
 * <p>
 * Keeping appearance separate from the game id is what lets one registered
 * block serve every game, so adding a game stays a matter of writing one
 * class rather than registering a block, an item, a model, and a recipe.
 */
public enum TableVariant implements StringRepresentable {

    /** No game assigned yet. */
    BLANK,

    /** Roulette and anything else built around a spinning wheel. */
    WHEEL,

    /** Card games: poker, dompe, blackjack, Uno. */
    CARDS,

    /** Slot machines and reel games. */
    SLOTS,

    /** Dice games. */
    DICE;

    private final String serializedName = name().toLowerCase(Locale.ROOT);

    /**
     * Name as it appears in block state and model paths. Changing one of
     * these orphans the matching model file.
     */
    @Override
    public @NotNull String getSerializedName() {
        return serializedName;
    }

    /** Same string for building asset paths. */
    public String assetName() {
        return serializedName;
    }
}