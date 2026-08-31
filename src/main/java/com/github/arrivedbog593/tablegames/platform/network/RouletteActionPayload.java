package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.PocketColor;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
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
 * Something a player asks to do at a roulette table.
 * <p>
 * Everything here is a request. The table checks the table maximum, the house
 * bankroll, and the player's balance before accepting anything, because a
 * client can send whatever it likes and will eventually try to.
 *
 * @param kind         0 places, 1 clear
 * @param tablePos     which table; verified against reach before use
 * @param betType      ordinal of the {@link BetType}, for a placement
 * @param targetNumber the pocket for a straight-up bet
 * @param targetDoubleZero whether that pocket is the double zero
 * @param amount       credits wagered
 */
public record RouletteActionPayload(int kind, BlockPos tablePos, int betType,
                                    int targetNumber, boolean targetDoubleZero, long amount)
        implements CustomPacketPayload {

    public static final int KIND_PLACE = 0;
    public static final int KIND_CLEAR = 1;

    /**
     * How far a player may be from a table and still act on it. Generous
     * enough for lag, tight enough that nobody bets from another room.
     */
    private static final double REACH_SQUARED = 64.0;

    public static final Type<RouletteActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "roulette_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RouletteActionPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RouletteActionPayload::kind,
                    BlockPos.STREAM_CODEC, RouletteActionPayload::tablePos,
                    ByteBufCodecs.VAR_INT, RouletteActionPayload::betType,
                    ByteBufCodecs.VAR_INT, RouletteActionPayload::targetNumber,
                    ByteBufCodecs.BOOL, RouletteActionPayload::targetDoubleZero,
                    ByteBufCodecs.VAR_LONG, RouletteActionPayload::amount,
                    RouletteActionPayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static RouletteActionPayload place(BlockPos pos, BetType type,
                                              Pocket target, long amount) {
        return new RouletteActionPayload(KIND_PLACE, pos, type.ordinal(),
                target == null ? 0 : target.number(),
                target != null && target.doubleZero(), amount);
    }

    public static RouletteActionPayload clear(BlockPos pos) {
        return new RouletteActionPayload(KIND_CLEAR, pos, 0, 0, false, 0);
    }

    /** Applied on the server, with every claim in it treated as a claim. */
    public static void handleOnServer(RouletteActionPayload payload, IPayloadContext context) {
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
            // Only players who actually opened this table act on it. Both
            // branches below also require a seat, but failing here first
            // keeps a client from probing tables it never looked at.
            if (!table.isPresent(player.getUUID())) {
                return;
            }

            switch (payload.kind()) {
                case KIND_CLEAR -> clear(player, table);
                case KIND_PLACE -> place(payload, player, table);
                default -> {
                }
            }
        });
    }

    /**
     * Takes a player's chips back off the layout.
     * <p>
     * Refused once betting closes, which used to be checked nowhere at all.
     * Withdrawing during the lockout is the same hole as standing up during
     * it: a way to pull a stake out of a round that is about to resolve.
     */
    private static void clear(ServerPlayer player, TableBlockEntity table) {
        if (!table.isSeated(player.getUUID())) {
            return;
        }
        if (!table.clearBets(player.getUUID()) && !table.isBettingOpen()) {
            player.displayClientMessage(
                    Component.translatable("tablegames.roulette.betting_closed"), true);
        }
    }

    private static void place(RouletteActionPayload payload, ServerPlayer player,
                              TableBlockEntity table) {
        BetType[] types = BetType.values();
        if (payload.betType() < 0 || payload.betType() >= types.length
                || payload.amount() <= 0) {
            return;
        }
        BetType type = types[payload.betType()];

        RouletteBet bet;
        try {
            if (type.requiresTarget()) {
                bet = RouletteBet.straightUp(pocketOf(payload), payload.amount());
            } else {
                bet = RouletteBet.outside(type, payload.amount());
            }
        } catch (RuntimeException malformed) {
            // A bet the record itself refuses to hold is a client sending
            // nonsense, not a player making a mistake worth reporting.
            return;
        }

        Component refusal = table.placeBet(player, bet);
        if (refusal != null) {
            player.displayClientMessage(refusal, true);
        }
    }

    /** Rebuilds the named pocket, with the color the wheel actually gives it. */
    private static Pocket pocketOf(RouletteActionPayload payload) {
        int number = Math.clamp(payload.targetNumber(), 0, 36);
        if (number == 0) {
            return payload.targetDoubleZero() ? Pocket.doubleZeroPocket() : Pocket.zero();
        }
        return Pocket.of(number, isRed(number) ? PocketColor.RED : PocketColor.BLACK);
    }

    private static final int[] RED_NUMBERS = {
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    };

    private static boolean isRed(int number) {
        for (int red : RED_NUMBERS) {
            if (red == number) {
                return true;
            }
        }
        return false;
    }
}
