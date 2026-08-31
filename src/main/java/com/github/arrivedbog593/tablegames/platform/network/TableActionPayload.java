package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.table.SeatChange;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

/**
 * Sitting down, standing up, or declaring yourself finished betting.
 * <p>
 * Separate from {@link RouletteActionPayload} because none of it is about
 * roulette. Every game gets seats and a ready button, and a payload named
 * after one game would have to be either copied or misused by the next.
 * <p>
 * As always, a request rather than an instruction: the table re-checks the
 * seat count, the round's phase, and whether the sender is even present.
 *
 * @param kind     what they are asking for
 * @param tablePos which table; verified against reach before use
 */
public record TableActionPayload(int kind, BlockPos tablePos) implements CustomPacketPayload {

    public static final int KIND_SIT = 0;
    public static final int KIND_STAND = 1;
    public static final int KIND_READY = 2;
    public static final int KIND_NOT_READY = 3;

    /**
     * How far a player may be from a table and still act on it. Generous
     * enough for lag, tight enough that nobody plays from another room.
     */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<TableActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "table_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TableActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, TableActionPayload::kind,
                    BlockPos.STREAM_CODEC, TableActionPayload::tablePos,
                    TableActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(TableActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(payload.tablePos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.tablePos())
                    instanceof TableBlockEntity table)) {
                return;
            }
            // Nobody who never opened the table is at it, whatever
            // their client claims. Without this a player could sit at every
            // table in a casino at once from across the room.
            if (!table.isPresent(player.getUUID())) {
                return;
            }

            switch (payload.kind()) {
                case KIND_SIT -> report(player, table.sit(player.getUUID()));
                case KIND_STAND -> report(player, table.stand(player.getUUID()));
                case KIND_READY -> table.setReady(player.getUUID(), true);
                case KIND_NOT_READY -> table.setReady(player.getUUID(), false);
                default -> {
                }
            }
        });
    }

    /**
     * Tells the player why a seat change did not happen.
     * <p>
     * Only on refusals. A successful sit is visible on the screen a tick
     * later and does not need narrating in chat.
     */
    private static void report(ServerPlayer player, SeatChange change) {
        if (!change.changed()) {
            player.sendSystemMessage(Component.translatable(change.translationKey()));
        }
    }
}
