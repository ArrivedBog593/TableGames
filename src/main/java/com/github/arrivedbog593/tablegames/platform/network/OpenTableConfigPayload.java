package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Tells a client to open a table's configuration screen.
 * <p>
 * Carries values only. The specs that give them meaning come from the game
 * registry, which both sides build identically at startup, so the screen can
 * lay out any game's settings without the server describing them.
 *
 * @param tablePos   where the table is, sent back with the change
 * @param gameId     what it hosts; the screen only opens once there is one
 * @param values     every stored setting, pending change included
 * @param configured whether the table is already open for play, which makes
 *                   going back to the list of games cost a round
 * @param changeable whether this block could host another game at all, so
 *                   the screen knows whether a way back exists to offer
 */
public record OpenTableConfigPayload(BlockPos tablePos, String gameId, Map<String, Long> values,
                                     boolean configured, boolean changeable)
        implements CustomPacketPayload {

    public static final Type<OpenTableConfigPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "open_table_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenTableConfigPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, OpenTableConfigPayload::tablePos,
                    ByteBufCodecs.STRING_UTF8, OpenTableConfigPayload::gameId,
                    ByteBufCodecs.map(HashMap::new, ByteBufCodecs.STRING_UTF8,
                            ByteBufCodecs.VAR_LONG), OpenTableConfigPayload::values,
                    ByteBufCodecs.BOOL, OpenTableConfigPayload::configured,
                    ByteBufCodecs.BOOL, OpenTableConfigPayload::changeable,
                    OpenTableConfigPayload::new);

    public static OpenTableConfigPayload of(GameBlockEntity table) {
        return new OpenTableConfigPayload(table.getBlockPos(), table.gameId(),
                table.settings().values(), table.isConfigured(), table.mayChangeGame());
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnClient(OpenTableConfigPayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.TableConfigScreen.open(payload));
    }
}
