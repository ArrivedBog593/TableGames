package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Tells a client to show the games a table can host, the first step of
 * setting one up.
 * <p>
 * Carries the ids of the games this player may put on the table, decided on
 * the server. What each game is — its name, its players — comes from the
 * registry, which both sides build identically at startup.
 *
 * @param tablePos which table, sent back with the choice
 * @param gameIds  the games offered, in registry order; may be empty
 */
public record OpenGamePickerPayload(BlockPos tablePos, List<String> gameIds)
        implements CustomPacketPayload {

    /** More than the registry will ever hold, and a bound on what the client reads. */
    private static final int MAX_GAMES = 64;

    public static final Type<OpenGamePickerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "open_game_picker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGamePickerPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, OpenGamePickerPayload::tablePos,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(MAX_GAMES)),
                    OpenGamePickerPayload::gameIds,
                    OpenGamePickerPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(OpenGamePickerPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.TableGamePickerScreen.open(payload));
    }
}
