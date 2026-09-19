package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Tells a client to show the games a table can host, the first step of
 * setting one up.
 * <p>
 * Carries only where the table is. The games come from the registry, which
 * both sides build identically at startup.
 *
 * @param tablePos which table, sent back with the choice
 */
public record OpenGamePickerPayload(BlockPos tablePos) implements CustomPacketPayload {

    public static final Type<OpenGamePickerPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "open_game_picker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenGamePickerPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, OpenGamePickerPayload::tablePos,
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
