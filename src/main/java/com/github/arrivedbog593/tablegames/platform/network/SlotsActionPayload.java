package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.block.SlotCabinet;
import com.github.arrivedbog593.tablegames.platform.block.SlotMachineBlockEntity;
import net.minecraft.ChatFormatting;
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
 * A pull of the lever.
 * <p>
 * The only thing a client can ask a slot machine to do, and still a request
 * rather than an instruction: how many lines and how much a line are both
 * checked against the machine's limits, the player's stack and the house
 * bankroll before a single reel turns. A client can send whatever it likes
 * and will eventually try to.
 * <p>
 * Sitting down, topping up and standing up are not here. They are the same
 * acts at a machine as at a table, and go over {@link TableActionPayload}.
 *
 * @param machinePos which machine; verified against reach before use
 * @param lines      how many paylines to play, 1 to {@code Payline.MAX}
 * @param perLine    the stake on each line
 */
public record SlotsActionPayload(BlockPos machinePos, int lines, long perLine)
        implements CustomPacketPayload {

    /**
     * How far a player may be from a machine and still pull it. Generous
     * enough for lag, tight enough that nobody plays from another room.
     */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<SlotsActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "slots_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SlotsActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SlotsActionPayload::machinePos,
                    ByteBufCodecs.VAR_INT, SlotsActionPayload::lines,
                    ByteBufCodecs.VAR_LONG, SlotsActionPayload::perLine,
                    SlotsActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleOnServer(SlotsActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(payload.machinePos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.machinePos())
                    instanceof SlotMachineBlockEntity machine)) {
                return;
            }
            // Only a player who actually opened this machine pulls it. The
            // seat is checked again below; failing here first keeps a client
            // from probing cabinets it never looked at.
            if (!machine.isPresent(player.getUUID())) {
                return;
            }
            SlotCabinet cabinet = machine.reels().orElse(null);
            if (cabinet == null) {
                return;
            }

            Component refusal = cabinet.spin(player, payload.lines(), payload.perLine());
            if (refusal != null) {
                player.sendSystemMessage(refusal.copy().withStyle(ChatFormatting.RED));
                return;
            }
            machine.markDirty();
        });
    }
}
