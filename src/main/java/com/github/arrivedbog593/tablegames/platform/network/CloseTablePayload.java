package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Says a player has stopped watching a table.
 * <p>
 * A menu-based screen tells the server when it closes for free. A plain
 * screen does not, so without this the table would keep sending state to
 * somebody who walked away, forever.
 * <p>
 * Closing the screen does not give up a seat. It starts an absence clock
 * instead: there is no way to tell a misclick from an exit, and losing your
 * chair to a stray Escape is the worst of the two mistakes. Reopening the
 * table cancels it.
 */
public record CloseTablePayload(BlockPos tablePos) implements CustomPacketPayload {

    public static final Type<CloseTablePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "close_table"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CloseTablePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, CloseTablePayload::tablePos,
                    CloseTablePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(CloseTablePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.level().getBlockEntity(payload.tablePos())
                    instanceof TableBlockEntity table) {
                table.leaveScreen(player.getUUID());
            }
        });
    }
}
