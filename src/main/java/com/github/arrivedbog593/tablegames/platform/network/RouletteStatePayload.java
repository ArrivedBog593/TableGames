package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A roulette round as one player is allowed to see it.
 * <p>
 * Built per player and carrying only that player's own wagers. Roulette has
 * no hidden information worth protecting, but the shape is set here on
 * purpose: every game payload that follows will be built the same way, and
 * poker's will absolutely have cards in it that its recipient must not see.
 * <p>
 * The balance rides along rather than travelling in a menu's data slots,
 * since a table has no menu. It also saves splitting a long into two ints.
 * <p>
 * The winning pocket is one integer rather than the three fields it reads
 * as, because {@code StreamCodec.composite} stops at six components. Packing
 * it is not only a workaround: "which pocket, or none" really is one value,
 * and three fields left room for the impossible combination of no result and
 * a double zero.
 *
 * @param balance      the viewer's credits
 * @param tableMaximum the largest wager this table will take right now
 * @param bettingOpen  whether chips may still be placed
 * @param secondsLeft  time left in the betting window, zero when idle
 * @param result       {@link #NO_RESULT}, a number from 0 to 36, or
 *                     {@link #DOUBLE_ZERO}
 * @param myBets       this player's own wagers
 */
public record RouletteStatePayload(long balance, long tableMaximum, boolean bettingOpen,
                                   int secondsLeft, int result, List<Wager> myBets)
        implements CustomPacketPayload {

    /** No pocket is being shown. */
    public static final int NO_RESULT = -1;

    /** The American double zero, which is not the number 0. */
    public static final int DOUBLE_ZERO = 37;

    public static final Type<RouletteStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "roulette_state"));

    /**
     * One wager on the wire.
     *
     * @param betType          ordinal of the {@link BetType}
     * @param targetNumber     the pocket for a straight-up bet, else zero
     * @param targetDoubleZero whether that pocket is the double zero
     */
    public record Wager(int betType, int targetNumber, boolean targetDoubleZero, long amount) {
        public static final StreamCodec<ByteBuf, Wager> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Wager::betType,
                ByteBufCodecs.VAR_INT, Wager::targetNumber,
                ByteBufCodecs.BOOL, Wager::targetDoubleZero,
                ByteBufCodecs.VAR_LONG, Wager::amount,
                Wager::new);

        public BetType type() {
            return BetType.values()[Math.clamp(betType, 0, BetType.values().length - 1)];
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RouletteStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, RouletteStatePayload::balance,
                    ByteBufCodecs.VAR_LONG, RouletteStatePayload::tableMaximum,
                    ByteBufCodecs.BOOL, RouletteStatePayload::bettingOpen,
                    ByteBufCodecs.VAR_INT, RouletteStatePayload::secondsLeft,
                    ByteBufCodecs.VAR_INT, RouletteStatePayload::result,
                    Wager.STREAM_CODEC.apply(ByteBufCodecs.list()), RouletteStatePayload::myBets,
                    RouletteStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** An idle table, for the client to draw before the first packet lands. */
    public static RouletteStatePayload idle() {
        return new RouletteStatePayload(0, 0, true, 0, NO_RESULT, List.of());
    }

    // --- Reading the packed result -------------------------------------------

    public boolean hasResult() {
        return result >= 0;
    }

    /** The winning number. Zero for either green pocket. */
    public int resultNumber() {
        return result == DOUBLE_ZERO ? 0 : Math.max(0, result);
    }

    public boolean resultDoubleZero() {
        return result == DOUBLE_ZERO;
    }

    /** How the winning pocket should be written: "0", "00" or "17". */
    public String resultLabel() {
        return resultDoubleZero() ? "00" : String.valueOf(resultNumber());
    }

    // --- Building it ------------------------------------------------------------

    /** Snapshots a table for one player. Server side only. */
    public static RouletteStatePayload forPlayer(MinecraftServer server,
                                                 TableBlockEntity table, UUID playerId) {
        List<Wager> wagers = new ArrayList<>();
        for (RouletteBet bet : table.betsOf(playerId)) {
            Pocket target = bet.target();
            wagers.add(new Wager(
                    bet.type().ordinal(),
                    target == null ? 0 : target.number(),
                    target != null && target.doubleZero(),
                    bet.amount()));
        }

        Pocket landed = table.lastResult().orElse(null);
        int packed = NO_RESULT;
        if (landed != null) {
            packed = landed.doubleZero() ? DOUBLE_ZERO : landed.number();
        }

        return new RouletteStatePayload(
                CreditStorage.get(server).balanceOf(playerId),
                table.currentTableMaximum(server),
                table.isBettingOpen(),
                table.secondsRemaining(),
                packed,
                List.copyOf(wagers));
    }

    /** Handed to the client's holder for the screen to read. */
    public static void handleOnClient(RouletteStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientRouletteState.accept(payload));
    }
}