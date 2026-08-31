package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.block.TableBlockEntity;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A roulette round as one player is allowed to see it.
 * <p>
 * Split in two on purpose. {@link TableView} is what anybody at the table may
 * see — who is sitting, what they staked, how long is left. {@link
 * RouletteView} carries what is addressed to one recipient: their balance,
 * their own wagers.
 * <p>
 * Roulette has nothing worth hiding, so today the split earns nothing. It is
 * here anyway because poker's payload will have cards in it, and a payload
 * that grew one flat field at a time is one where somebody eventually adds a
 * hand next to a spectator count and ships it to the whole table.
 * <p>
 * The shape has to exist before there is something to protect; retrofitting it around
 * live-hidden information is how the leak happens.
 * <p>
 * The nesting is also what keeps each record under the six components
 * {@code StreamCodec.composite} allows. That limit usually means two fields
 * are secretly the same data — here it means two records are secretly
 * different audiences.
 */
public record RouletteStatePayload(TableView table, RouletteView roulette)
        implements CustomPacketPayload {

    /** No pocket is being shown. */
    public static final int NO_RESULT = -1;

    /** The American double zero, which is not the number 0. */
    public static final int DOUBLE_ZERO = 37;

    /** Sent when the viewer holds no seat. */
    public static final int NO_SEAT = -1;

    /**
     * Hard ceilings on the wire.
     * <p>
     * Not decoration. Every list here is built from state a client can grow
     * by sending packets, and an unbounded list codec turns "place a bet"
     * into a way to make the server serialize something enormous to every
     * viewer. Both limits sit well above anything a real table reaches.
     */
    public static final int MAX_BETS_ON_WIRE = 64;
    public static final int MAX_SEATS_ON_WIRE = 16;

    public static final Type<RouletteStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "roulette_state"));

    // --- The public view ---------------------------------------------------------

    /**
     * One seated player as everyone else sees them.
     *
     * @param playerId whose seat this is
     * @param name     their display name, resolved server side
     * @param staked   what they have on the layout this round
     * @param ready    whether they have declared themselves finished
     */
    public record SeatView(UUID playerId, String name, long staked, boolean ready) {

        public static final StreamCodec<ByteBuf, SeatView> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, SeatView::playerId,
                ByteBufCodecs.STRING_UTF8, SeatView::name,
                ByteBufCodecs.VAR_LONG, SeatView::staked,
                ByteBufCodecs.BOOL, SeatView::ready,
                SeatView::new);
    }

    /**
     * The table itself: who is at it and where the round stands.
     *
     * @param phase          ordinal of the {@link RoundPhase}
     * @param secondsLeft    time until the wheel turns, zero when idle
     * @param seats          seated players in turn order
     * @param spectatorCount how many are watching without a seat
     * @param mySeat         the recipient's seat index, or {@link #NO_SEAT}
     * @param maxSeats       how many seats this game has
     */
    public record TableView(int phase, int secondsLeft, List<SeatView> seats,
                            int spectatorCount, int mySeat, int maxSeats) {

        public static final StreamCodec<ByteBuf, TableView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TableView::phase,
                ByteBufCodecs.VAR_INT, TableView::secondsLeft,
                SeatView.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SEATS_ON_WIRE)),
                TableView::seats,
                ByteBufCodecs.VAR_INT, TableView::spectatorCount,
                ByteBufCodecs.VAR_INT, TableView::mySeat,
                ByteBufCodecs.VAR_INT, TableView::maxSeats,
                TableView::new);

        public RoundPhase roundPhase() {
            RoundPhase[] all = RoundPhase.values();
            return all[Math.clamp(phase, 0, all.length - 1)];
        }

        public boolean isSeated() {
            return mySeat != NO_SEAT;
        }

        public boolean hasFreeSeat() {
            return seats.size() < maxSeats;
        }
    }

    /** Another player's chips, so a spectator sees the whole felt. */
    public record SeatBets(int seatIndex, List<Wager> wagers) {

        public static final StreamCodec<ByteBuf, SeatBets> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SeatBets::seatIndex,
                Wager.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BETS_ON_WIRE)), SeatBets::wagers,
                SeatBets::new);
    }

    // --- The private view ----------------------------------------------------------

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
            BetType[] all = BetType.values();
            return all[Math.clamp(betType, 0, all.length - 1)];
        }
    }

    /**
     * What is addressed to the recipient alone.
     *
     * @param balance      the viewer's credits, never anybody else's
     * @param tableMinimum the smallest wager this table will take
     * @param tableMaximum the largest wager this table will take right now
     * @param result       {@link #NO_RESULT}, 0 to 36, or {@link #DOUBLE_ZERO}
     * @param myBets       the viewer's own wagers
     * @param otherBets    everybody else's, by seat, so the felt is complete
     */
    public record RouletteView(long balance, long tableMinimum, long tableMaximum,
                               int result, List<Wager> myBets, List<SeatBets> otherBets) {

        public static final StreamCodec<ByteBuf, RouletteView> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, RouletteView::balance,
                        ByteBufCodecs.VAR_LONG, RouletteView::tableMinimum,
                        ByteBufCodecs.VAR_LONG, RouletteView::tableMaximum,
                        ByteBufCodecs.VAR_INT, RouletteView::result,
                        Wager.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BETS_ON_WIRE)),
                        RouletteView::myBets,
                        SeatBets.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_SEATS_ON_WIRE)),
                        RouletteView::otherBets,
                        RouletteView::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, RouletteStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    TableView.STREAM_CODEC, RouletteStatePayload::table,
                    RouletteView.STREAM_CODEC, RouletteStatePayload::roulette,
                    RouletteStatePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** An idle table for the client to draw before the first packet lands. */
    public static RouletteStatePayload idle() {
        return new RouletteStatePayload(
                new TableView(RoundPhase.IDLE.ordinal(), 0, List.of(), 0, NO_SEAT, 0),
                new RouletteView(0, 0, 0, NO_RESULT, List.of(), List.of()));
    }

    // --- Convenience for the screen ---------------------------------------------------

    public long balance() {
        return roulette.balance();
    }

    public long tableMaximum() {
        return roulette.tableMaximum();
    }

    public long tableMinimum() {
        return roulette.tableMinimum();
    }

    public int secondsLeft() {
        return table.secondsLeft();
    }

    public RoundPhase phase() {
        return table.roundPhase();
    }

    /** Whether a wager may be placed or withdrawn right now. */
    public boolean bettingOpen() {
        return table.roundPhase().acceptsBets();
    }

    /** Whether the round is in its final seconds and nothing may change. */
    public boolean locked() {
        return table.roundPhase() == RoundPhase.LOCKED;
    }

    public boolean isSeated() {
        return table.isSeated();
    }

    public List<Wager> myBets() {
        return roulette.myBets();
    }

    public boolean hasResult() {
        return roulette.result() >= 0;
    }

    /** The winning number. Zero for either green pocket. */
    public int resultNumber() {
        return roulette.result() == DOUBLE_ZERO ? 0 : Math.max(0, roulette.result());
    }

    public boolean resultDoubleZero() {
        return roulette.result() == DOUBLE_ZERO;
    }

    /** How the winning pocket should be written: "0", "00" or "17". */
    public String resultLabel() {
        return resultDoubleZero() ? "00" : String.valueOf(resultNumber());
    }

    // --- Building it --------------------------------------------------------------------

    /** Snapshots a table for one player. Server side only. */
    public static RouletteStatePayload forPlayer(MinecraftServer server,
                                                 TableBlockEntity table, UUID playerId) {
        List<SeatView> seatViews = new ArrayList<>();
        List<SeatBets> otherBets = new ArrayList<>();

        List<UUID> seated = table.seatedPlayers();
        for (int index = 0; index < seated.size() && index < MAX_SEATS_ON_WIRE; index++) {
            UUID occupant = seated.get(index);
            seatViews.add(new SeatView(
                    occupant,
                    nameOf(server, occupant),
                    table.wageredBy(occupant),
                    table.isReady(occupant)));

            if (!occupant.equals(playerId)) {
                List<Wager> theirs = wagersOf(table, occupant);
                if (!theirs.isEmpty()) {
                    otherBets.add(new SeatBets(index, theirs));
                }
            }
        }

        TableView view = new TableView(
                table.phase().ordinal(),
                table.secondsRemaining(),
                List.copyOf(seatViews),
                table.spectatorCount(),
                table.seatIndexOf(playerId).orElse(NO_SEAT),
                table.maxSeats());

        Pocket landed = table.lastResult().orElse(null);
        int packed = NO_RESULT;
        if (landed != null) {
            packed = landed.doubleZero() ? DOUBLE_ZERO : landed.number();
        }

        RouletteView mine = new RouletteView(
                CreditStorage.get(server).balanceOf(playerId),
                table.effectiveMinimum(BetType.STRAIGHT_UP),
                table.currentTableMaximum(server),
                packed,
                wagersOf(table, playerId),
                List.copyOf(otherBets));

        return new RouletteStatePayload(view, mine);
    }

    private static List<Wager> wagersOf(TableBlockEntity table, UUID playerId) {
        List<Wager> wagers = new ArrayList<>();
        for (RouletteBet bet : table.betsOf(playerId)) {
            if (wagers.size() >= MAX_BETS_ON_WIRE) {
                break;
            }
            Pocket target = bet.target();
            wagers.add(new Wager(
                    bet.type().ordinal(),
                    target == null ? 0 : target.number(),
                    target != null && target.doubleZero(),
                    bet.amount()));
        }
        return List.copyOf(wagers);
    }

    /**
     * A seated player's name, resolved here rather than on the client.
     * <p>
     * A spectator can be watching a table whose players their client is not
     * tracking, so a client-side lookup would show blanks in exactly the case
     * the spectator view exists for.
     */
    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.getGameProfile().getName();
        }
        if (server.getProfileCache() == null) {
            return "?";
        }
        return server.getProfileCache().get(playerId)
                .map(GameProfile::getName)
                .orElse("?");
    }

    /** Handed to the client's holder for the screen to read. */
    public static void handleOnClient(RouletteStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientRouletteState.accept(payload));
    }
}
