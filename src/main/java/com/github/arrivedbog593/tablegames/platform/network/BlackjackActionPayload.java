package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackAction;
import com.github.arrivedbog593.tablegames.platform.block.BlackjackTable;
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
 * Something a player asks to do at a blackjack table.
 * <p>
 * A request like every other: the table checks the seat, the turn, the stack
 * and the bankroll before anything happens, because a client can send a
 * double on somebody else's turn and eventually will.
 *
 * @param kind     what they are asking for
 * @param tablePos which table; verified against reach before use
 * @param amount   credits to add to the opening wager; zero for everything else
 */
public record BlackjackActionPayload(int kind, BlockPos tablePos, long amount)
        implements CustomPacketPayload {

    public static final int KIND_BET = 0;
    public static final int KIND_CLEAR = 1;
    public static final int KIND_HIT = 2;
    public static final int KIND_STAND = 3;
    public static final int KIND_DOUBLE = 4;
    public static final int KIND_SPLIT = 5;
    public static final int KIND_INSURE = 6;
    public static final int KIND_DECLINE_INSURANCE = 7;

    /** As at roulette: generous enough for lag, tight enough that nobody plays from another room. */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<BlackjackActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "blackjack_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, BlackjackActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BlackjackActionPayload::kind,
                    BlockPos.STREAM_CODEC, BlackjackActionPayload::tablePos,
                    ByteBufCodecs.VAR_LONG, BlackjackActionPayload::amount,
                    BlackjackActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static BlackjackActionPayload of(int kind, BlockPos pos) {
        return new BlackjackActionPayload(kind, pos, 0);
    }

    public static BlackjackActionPayload bet(BlockPos pos, long amount) {
        return new BlackjackActionPayload(KIND_BET, pos, amount);
    }

    public static void handleOnServer(BlackjackActionPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (player.distanceToSqr(payload.tablePos().getCenter()) > REACH_SQUARED) {
                return;
            }
            if (!(player.level().getBlockEntity(payload.tablePos()) instanceof TableBlockEntity table)) {
                return;
            }
            if (!table.isPresent(player.getUUID()) || !table.isSeated(player.getUUID())) {
                return;
            }
            if (table.blackjack().isEmpty()) {
                return;
            }
            BlackjackTable cards = table.blackjack().get();
            Component refusal = switch (payload.kind()) {
                case KIND_BET -> payload.amount() > 0 ? cards.placeBet(player, payload.amount()) : null;
                case KIND_CLEAR -> cards.clearBet(player.getUUID()) || cards.isBettingOpen()
                        ? null
                        : Component.translatable("tablegames.blackjack.betting_closed");
                case KIND_HIT -> cards.act(player, new BlackjackAction.Hit());
                case KIND_STAND -> cards.act(player, new BlackjackAction.Stand());
                case KIND_DOUBLE -> cards.act(player, new BlackjackAction.DoubleDown());
                case KIND_SPLIT -> cards.act(player, new BlackjackAction.Split());
                case KIND_INSURE -> cards.act(player, new BlackjackAction.Insurance(true));
                case KIND_DECLINE_INSURANCE -> cards.act(player, new BlackjackAction.Insurance(false));
                default -> null;
            };
            TableNoticePayload.tell(player, refusal);
        });
    }
}
