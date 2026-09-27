package com.github.arrivedbog593.tablegames.platform.block;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A result a test table can be told to land on next.
 * <p>
 * Described by the game, laid out by the test panel, which knows nothing
 * about any particular game: a slot machine offers its combinations, a wheel
 * its pockets, and whatever comes next offers whatever it has, with no change
 * to the panel.
 *
 * @param id    stable name, sent back when the tester picks it
 * @param label what the panel calls it
 * @param color how the panel paints it, as RGB — a pocket's own felt color,
 *              a symbol's own tint
 */
public record RigOption(String id, Component label, int color) {

    public static final StreamCodec<RegistryFriendlyByteBuf, RigOption> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), RigOption::id,
                    ComponentSerialization.STREAM_CODEC, RigOption::label,
                    ByteBufCodecs.INT, RigOption::color,
                    RigOption::new);
}
