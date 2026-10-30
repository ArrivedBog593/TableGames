package com.github.arrivedbog593.tablegames.platform.network;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.engine.card.Card;
import com.github.arrivedbog593.tablegames.engine.card.Rank;
import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.card.Suit;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackAction;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackFormat;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackHand;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRules;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackSession;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackTournament;
import com.github.arrivedbog593.tablegames.engine.session.Action;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.platform.block.BlackjackTable;
import com.github.arrivedbog593.tablegames.platform.block.GameBlockEntity;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.ByteBuf;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A blackjack table as one player is allowed to see it.
 * <p>
 * Four parts. The seats are roulette's {@link RouletteStatePayload.TableView}:
 * who is sitting, what they have down, who is ready, how long is left — the
 * same at every table. {@link DealView} is the felt, the same for everybody
 * looking at it: every seat's cards and wagers, and the dealer's cards as far
 * as they are turned over. {@link FormatView} is what the table is played
 * for — who holds the bank, how a tournament stands — and is public too.
 * {@link MineView} is addressed to the recipient alone: their credits, and
 * which moves are theirs to make.
 * <p>
 * The hole card is the one thing at this table nobody may see, and it is not
 * hidden here — it is never put in. The felt is built from
 * {@link BlackjackSession#dealerShown()}, which leaves it out until the dealer
 * turns it. A player holding the bank does not see it early either: they
 * turn it when the seats are done, like any dealer.
 */
public record BlackjackStatePayload(RouletteStatePayload.TableView table, DealView deal, FormatView format,
                                    MineView mine)
        implements CustomPacketPayload {

    /** No hand on the felt: the table is taking wagers or idle. */
    public static final int NO_HAND = -1;

    /** Nobody is on the clock. */
    public static final int NOBODY = -1;

    /** A hand with no result yet. */
    public static final long NO_RESULT = Long.MIN_VALUE;

    /** Moves, as bits of {@link MineView#moves()}. */
    public static final int HIT = 1;
    public static final int STAND = 1 << 1;
    public static final int DOUBLE = 1 << 2;
    public static final int SPLIT = 1 << 3;
    public static final int INSURE = 1 << 4;

    /**
     * Hard ceilings on the wire. A dealer drawing from a single deck can in
     * principle reach eleven cards; nothing gets near sixteen.
     */
    public static final int MAX_CARDS_ON_WIRE = 16;

    public static final Type<BlackjackStatePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(TableGames.MOD_ID, "blackjack_state"));

    // --- Cards on the wire --------------------------------------------------------------

    /** A card as one number: its rank and its suit. */
    public static int code(Card card) {
        return card.rank().ordinal() * Suit.values().length + card.suit().ordinal();
    }

    /** The card a number stands for, clamped so a bad one is a wrong card rather than a crash. */
    public static Card card(int code) {
        int suits = Suit.values().length;
        int clamped = Math.clamp(code, 0, Rank.values().length * suits - 1);
        return new Card(Rank.values()[clamped / suits], Suit.values()[clamped % suits]);
    }

    private static List<Integer> codes(List<Card> cards) {
        List<Integer> codes = new ArrayList<>();
        for (Card card : cards) {
            if (codes.size() >= MAX_CARDS_ON_WIRE) {
                break;
            }
            codes.add(code(card));
        }
        return List.copyOf(codes);
    }

    public static List<Card> cards(List<Integer> codes) {
        return codes.stream().map(BlackjackStatePayload::card).toList();
    }

    // --- The felt -------------------------------------------------------------------------

    /**
     * One hand in front of one seat.
     *
     * @param cards    card codes, in the order dealt
     * @param bet      what rides on it, doubled already when it was doubled
     * @param doubled  whether it was
     * @param finished whether nothing more can happen to it
     */
    public record HandView(List<Integer> cards, long bet, boolean doubled, boolean finished) {

        public static final StreamCodec<ByteBuf, HandView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_CARDS_ON_WIRE)), HandView::cards,
                ByteBufCodecs.VAR_LONG, HandView::bet,
                ByteBufCodecs.BOOL, HandView::doubled,
                ByteBufCodecs.BOOL, HandView::finished,
                HandView::new);

        public List<Card> asCards() {
            return BlackjackStatePayload.cards(cards);
        }

        public int total() {
            return BlackjackHand.totalOf(asCards());
        }
    }

    /**
     * Everything one seat has on the felt.
     *
     * @param seatIndex   which seat, as in the table's seat list
     * @param openingBet  the wager in the spot, while betting is open
     * @param hands       the hands dealt to it, in the order they are played
     * @param handOnClock which of them is being played, or is next
     * @param insurance   what was put on insurance, zero for none
     * @param result      what the hand came to once paid, or {@link #NO_RESULT}
     */
    public record SeatHands(int seatIndex, long openingBet, List<HandView> hands,
                            int handOnClock, long insurance, long result) {

        public static final StreamCodec<ByteBuf, SeatHands> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SeatHands::seatIndex,
                ByteBufCodecs.VAR_LONG, SeatHands::openingBet,
                HandView.STREAM_CODEC.apply(ByteBufCodecs.list(BlackjackRules.MAX_HANDS)), SeatHands::hands,
                ByteBufCodecs.VAR_INT, SeatHands::handOnClock,
                ByteBufCodecs.VAR_LONG, SeatHands::insurance,
                ByteBufCodecs.VAR_LONG, SeatHands::result,
                SeatHands::new);

        public boolean hasResult() {
            return result != NO_RESULT;
        }
    }

    /**
     * The rules on the placard, and how far into the shoe the table is.
     *
     * @param decks         decks in the shoe
     * @param hitsSoft17    whether the dealer draws to a soft seventeen
     * @param naturalPayout ordinal of what a blackjack pays
     * @param shoeLeft      cards still to deal, or zero before the first hand
     * @param shoeSize      cards in a full shoe
     */
    public record RulesView(int decks, boolean hitsSoft17, int naturalPayout, int shoeLeft, int shoeSize) {

        public static final StreamCodec<ByteBuf, RulesView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RulesView::decks,
                ByteBufCodecs.BOOL, RulesView::hitsSoft17,
                ByteBufCodecs.VAR_INT, RulesView::naturalPayout,
                ByteBufCodecs.VAR_INT, RulesView::shoeLeft,
                ByteBufCodecs.VAR_INT, RulesView::shoeSize,
                RulesView::new);
    }

    /**
     * The felt, the same for everybody looking at it.
     *
     * @param stage       ordinal of the hand's stage, or {@link #NO_HAND}
     * @param dealer      the dealer's cards that are face up
     * @param holeHidden  whether a card lies face down beside them
     * @param onClockSeat the seat whose turn it is, or {@link #NOBODY}
     * @param seats       every seat with a wager or a hand
     * @param rules       the placard
     */
    public record DealView(int stage, List<Integer> dealer, boolean holeHidden, int onClockSeat,
                           List<SeatHands> seats, RulesView rules) {

        public static final StreamCodec<ByteBuf, DealView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, DealView::stage,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_CARDS_ON_WIRE)), DealView::dealer,
                ByteBufCodecs.BOOL, DealView::holeHidden,
                ByteBufCodecs.VAR_INT, DealView::onClockSeat,
                SeatHands.STREAM_CODEC.apply(ByteBufCodecs.list(RouletteStatePayload.MAX_SEATS_ON_WIRE)),
                DealView::seats,
                RulesView.STREAM_CODEC, DealView::rules,
                DealView::new);

        public boolean hasHand() {
            return stage != NO_HAND;
        }

        public Optional<SeatHands> seat(int seatIndex) {
            return seats.stream().filter(seat -> seat.seatIndex() == seatIndex).findFirst();
        }
    }

    // --- What the table is played for --------------------------------------------------------

    /** Bits of {@link SeatScore#flags()}. */
    public static final int ENTRANT = 1;
    public static final int OUT = 1 << 1;
    public static final int WITHDRAWN = 1 << 2;
    public static final int CONFIRMED = 1 << 3;

    /** Bits of {@link FormatView#options()}. */
    public static final int BANK_BY_HAND = 1;
    public static final int BANK_WINS_TIES = 1 << 1;

    /** A tournament not yet started, being played, or decided and on show. */
    public static final int TOURNEY_LOBBY = 0;
    public static final int TOURNEY_LIVE = 1;
    public static final int TOURNEY_OVER = 2;

    /**
     * One seat's standing: tournament chips, or at a table played for fun
     * the running score.
     *
     * @param chips what they hold, or have won or lost
     * @param flags {@link #ENTRANT}, {@link #OUT}, {@link #WITHDRAWN}, {@link #CONFIRMED}
     * @param place where they finished, once a tournament is decided; zero before
     * @param prize what they won, once it is
     */
    public record SeatScore(int seatIndex, long chips, int flags, int place, long prize) {

        public static final StreamCodec<ByteBuf, SeatScore> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, SeatScore::seatIndex,
                ByteBufCodecs.VAR_LONG, SeatScore::chips,
                ByteBufCodecs.VAR_INT, SeatScore::flags,
                ByteBufCodecs.VAR_INT, SeatScore::place,
                ByteBufCodecs.VAR_LONG, SeatScore::prize,
                SeatScore::new);

        public boolean has(int flag) {
            return (flags & flag) != 0;
        }
    }

    /**
     * Where a tournament stands.
     *
     * @param handNumber the hand being played, counted from one
     * @param hands      how many it lasts
     * @param entry      what entering costs; zero when it is played for fun
     * @param pot        the entries paid in
     * @param state      {@link #TOURNEY_LOBBY}, {@link #TOURNEY_LIVE} or {@link #TOURNEY_OVER}
     */
    public record TourneyView(int handNumber, int hands, long entry, long pot, int state) {

        public static final StreamCodec<ByteBuf, TourneyView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, TourneyView::handNumber,
                ByteBufCodecs.VAR_INT, TourneyView::hands,
                ByteBufCodecs.VAR_LONG, TourneyView::entry,
                ByteBufCodecs.VAR_LONG, TourneyView::pot,
                ByteBufCodecs.VAR_INT, TourneyView::state,
                TourneyView::new);

        static final TourneyView NONE = new TourneyView(0, 0, 0, 0, TOURNEY_LOBBY);
    }

    /**
     * What the table is played for, the same for everybody.
     *
     * @param format     ordinal of the {@link BlackjackFormat}
     * @param forMoney   whether credits change hands
     * @param bankerSeat the seat holding the bank this hand — or, between
     *                   hands, the one that will — or {@link #NOBODY}
     * @param tourney    the tournament, at a tournament table
     * @param scores     each seat's chips or score, where there are any
     * @param options    how a player bank plays: {@link #BANK_BY_HAND}, {@link #BANK_WINS_TIES}
     */
    public record FormatView(int format, boolean forMoney, int bankerSeat, TourneyView tourney,
                             List<SeatScore> scores, int options) {

        public static final StreamCodec<ByteBuf, FormatView> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, FormatView::format,
                ByteBufCodecs.BOOL, FormatView::forMoney,
                ByteBufCodecs.VAR_INT, FormatView::bankerSeat,
                TourneyView.STREAM_CODEC, FormatView::tourney,
                SeatScore.STREAM_CODEC.apply(ByteBufCodecs.list(RouletteStatePayload.MAX_SEATS_ON_WIRE)),
                FormatView::scores,
                ByteBufCodecs.VAR_INT, FormatView::options,
                FormatView::new);

        public BlackjackFormat blackjackFormat() {
            return BlackjackFormat.values()[Math.clamp(format, 0, BlackjackFormat.values().length - 1)];
        }

        public boolean has(int option) {
            return (options & option) != 0;
        }

        public Optional<SeatScore> score(int seatIndex) {
            return scores.stream().filter(score -> score.seatIndex() == seatIndex).findFirst();
        }
    }

    // --- The recipient's own ----------------------------------------------------------------

    /**
     * What is addressed to the recipient alone.
     *
     * @param funds         the viewer's credits and what is spoken for
     * @param tableMinimum  the least opening wager
     * @param tableMaximum  the most, right now
     * @param moves         the moves open to the viewer, as bits
     * @param insuranceCost what insurance would cost them, zero when not offered
     */
    public record MineView(PlayerFunds funds, long tableMinimum, long tableMaximum, int moves,
                           long insuranceCost) {

        public static final StreamCodec<ByteBuf, MineView> STREAM_CODEC = StreamCodec.composite(
                PlayerFunds.STREAM_CODEC, MineView::funds,
                ByteBufCodecs.VAR_LONG, MineView::tableMinimum,
                ByteBufCodecs.VAR_LONG, MineView::tableMaximum,
                ByteBufCodecs.VAR_INT, MineView::moves,
                ByteBufCodecs.VAR_LONG, MineView::insuranceCost,
                MineView::new);

        public boolean may(int move) {
            return (moves & move) != 0;
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, BlackjackStatePayload> STREAM_CODEC =
            StreamCodec.composite(
                    RouletteStatePayload.TableView.STREAM_CODEC, BlackjackStatePayload::table,
                    DealView.STREAM_CODEC, BlackjackStatePayload::deal,
                    FormatView.STREAM_CODEC, BlackjackStatePayload::format,
                    MineView.STREAM_CODEC, BlackjackStatePayload::mine,
                    BlackjackStatePayload::new);

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** An empty table for the client to draw before the first packet lands. */
    public static BlackjackStatePayload idle() {
        return new BlackjackStatePayload(
                new RouletteStatePayload.TableView(RoundPhase.IDLE.ordinal(), 0, List.of(), 0,
                        RouletteStatePayload.NO_SEAT, 0),
                new DealView(NO_HAND, List.of(), false, NOBODY, List.of(),
                        new RulesView(BlackjackRules.STANDARD.decks(), false, 0, 0, 0)),
                new FormatView(BlackjackFormat.HOUSE.ordinal(), true, NOBODY, TourneyView.NONE, List.of(), 0),
                new MineView(new PlayerFunds(0, 0, 0, 0, 0, 1), 0, 0, 0, 0));
    }

    // --- Convenience for the screen -----------------------------------------------------------

    public RoundPhase phase() {
        return table.roundPhase();
    }

    public boolean isSeated() {
        return table.isSeated();
    }

    public int mySeat() {
        return table.mySeat();
    }

    public PlayerFunds funds() {
        return mine.funds();
    }

    public BlackjackFormat blackjackFormat() {
        return format.blackjackFormat();
    }

    /** Whether the viewer may put chips down right now. */
    public boolean bettingOpen() {
        return switch (blackjackFormat()) {
            case HOUSE -> !deal.hasHand() && table.roundPhase().acceptsBets();
            case TOURNAMENT -> !deal.hasHand() && format.tourney().state() == TOURNEY_LIVE
                    && mine.tableMaximum() > 0
                    && myScore().map(score -> !score.has(OUT)).orElse(false);
            case BANKER -> false;
        };
    }

    /** The viewer's seat on the felt, if they have a wager or a hand there. */
    public Optional<SeatHands> mySpot() {
        return isSeated() ? deal.seat(mySeat()) : Optional.empty();
    }

    /** The viewer's chips or score, if this table keeps any. */
    public Optional<SeatScore> myScore() {
        return isSeated() ? format.score(mySeat()) : Optional.empty();
    }

    /** Whether the viewer holds the bank, or is about to. */
    public boolean iAmBanker() {
        return isSeated() && format.bankerSeat() == mySeat();
    }

    // --- Building it --------------------------------------------------------------------------

    /** Snapshots a table for one player. Server side only. */
    public static BlackjackStatePayload forPlayer(MinecraftServer server, GameBlockEntity table,
                                                  BlackjackTable blackjack, UUID playerId) {
        List<UUID> seated = table.seatedPlayers();
        List<RouletteStatePayload.SeatView> seatViews = new ArrayList<>();
        for (int index = 0; index < seated.size() && index < RouletteStatePayload.MAX_SEATS_ON_WIRE; index++) {
            UUID occupant = seated.get(index);
            seatViews.add(new RouletteStatePayload.SeatView(occupant, nameOf(server, occupant),
                    table.wageredBy(occupant), table.isReady(occupant)));
        }
        RouletteStatePayload.TableView tableView = new RouletteStatePayload.TableView(
                table.phase().ordinal(), table.secondsRemaining(), List.copyOf(seatViews),
                table.spectatorCount(), table.seatIndexOf(playerId).orElse(RouletteStatePayload.NO_SEAT),
                table.maxSeats());

        BlackjackSession hand = blackjack.hand().orElse(null);
        List<SeatHands> seats = new ArrayList<>();
        int onClock = NOBODY;
        for (int index = 0; index < seated.size() && index < RouletteStatePayload.MAX_SEATS_ON_WIRE; index++) {
            UUID occupant = seated.get(index);
            if (hand != null && hand.seatOf(occupant).isPresent()) {
                List<HandView> hands = new ArrayList<>();
                for (BlackjackHand dealt : hand.handsOf(occupant)) {
                    hands.add(new HandView(codes(dealt.cards()), dealt.bet(), dealt.doubled(), dealt.finished()));
                }
                seats.add(new SeatHands(index, 0, List.copyOf(hands), hand.handOnClockIndex(occupant),
                        hand.insuranceOf(occupant), blackjack.resultOf(occupant).orElse(NO_RESULT)));
                if (hand.currentTurn().map(occupant::equals).orElse(false)) {
                    onClock = index;
                }
            } else if (hand != null && blackjack.bankerId().map(occupant::equals).orElse(false)) {
                // The bank has no hand of its own in front of its seat, only a result.
                seats.add(new SeatHands(index, 0, List.of(), 0, 0,
                        blackjack.resultOf(occupant).orElse(NO_RESULT)));
            } else if (blackjack.betOf(occupant) > 0) {
                seats.add(new SeatHands(index, blackjack.betOf(occupant), List.of(), 0, 0, NO_RESULT));
            }
        }

        BlackjackRules rules = blackjack.rules();
        Shoe shoe = blackjack.shoe();
        RulesView rulesView = new RulesView(rules.decks(), rules.dealerHitsSoft17(),
                rules.naturalPayout().ordinal(),
                shoe == null ? 0 : shoe.remaining(), shoe == null ? 0 : shoe.size());
        DealView deal = new DealView(
                hand == null ? NO_HAND : hand.stage().ordinal(),
                hand == null ? List.of() : codes(hand.dealerShown()),
                hand != null && hand.holeHidden(),
                onClock,
                List.copyOf(seats),
                rulesView);

        Optional<BuyIn> buyIn = table.buyIn();
        PlayerFunds funds = new PlayerFunds(
                table.funds().balanceOf(server, playerId),
                table.funds().stakes().committedElsewhere(playerId, table.commitmentKey()),
                table.stackOf(playerId),
                buyIn.map(BuyIn::minimum).orElse(0L),
                buyIn.map(BuyIn::maximum).orElse(0L),
                1L);
        boolean banking = hand != null && blackjack.bankerId().map(playerId::equals).orElse(false);
        int moves = blackjack.isDealing() || hand == null ? 0
                : banking ? bitsOf(hand.bankerMoves()) : bitsOf(hand.legalActions(playerId));
        MineView mine = new MineView(funds, blackjack.limits().minimum(),
                blackjack.currentMaximum(server, playerId), moves,
                hand == null ? 0 : hand.insuranceCost(playerId));

        return new BlackjackStatePayload(tableView, deal, formatView(table, blackjack, seated), mine);
    }

    private static FormatView formatView(GameBlockEntity table, BlackjackTable blackjack, List<UUID> seated) {
        int bankerSeat = blackjack.bankerId().or(blackjack::upcomingBanker)
                .map(seated::indexOf).filter(index -> index >= 0).orElse(NOBODY);

        TourneyView tourney = TourneyView.NONE;
        List<SeatScore> scores = new ArrayList<>();
        Optional<BlackjackTournament> running = blackjack.tournament();
        if (running.isPresent()) {
            BlackjackTournament tournament = running.get();
            boolean decided = blackjack.tournamentDecided();
            // Between paying a hand and betting the next, the felt still shows
            // the one just played, and so does the count.
            boolean showingPlayed = decided || blackjack.isPaid();
            int handNumber = Math.min(tournament.hands(),
                    tournament.handsPlayed() + (showingPlayed ? 0 : 1));
            tourney = new TourneyView(Math.max(1, handNumber), tournament.hands(), tournament.entry(),
                    tournament.pot(), decided ? TOURNEY_OVER : TOURNEY_LIVE);
            Map<UUID, BlackjackTournament.Standing> standings = new HashMap<>();
            if (decided) {
                tournament.standings().forEach(standing -> standings.put(standing.playerId(), standing));
            }
            for (int index = 0; index < seated.size() && index < RouletteStatePayload.MAX_SEATS_ON_WIRE; index++) {
                UUID occupant = seated.get(index);
                if (!tournament.isEntrant(occupant)) {
                    continue;
                }
                int flags = ENTRANT;
                flags |= tournament.isIn(occupant) ? 0 : OUT;
                flags |= tournament.hasWithdrawn(occupant) ? WITHDRAWN : 0;
                flags |= blackjack.hasConfirmed(occupant) ? CONFIRMED : 0;
                BlackjackTournament.Standing standing = standings.get(occupant);
                scores.add(new SeatScore(index, tournament.chipsOf(occupant), flags,
                        standing == null ? 0 : standing.place(), standing == null ? 0 : standing.prize()));
            }
        } else if (blackjack.format() == BlackjackFormat.TOURNAMENT) {
            TableSettings settings = table.settings();
            tourney = new TourneyView(0, blackjack.game().handsFrom(settings),
                    blackjack.game().entryFrom(settings), 0, TOURNEY_LOBBY);
        } else {
            for (int index = 0; index < seated.size() && index < RouletteStatePayload.MAX_SEATS_ON_WIRE; index++) {
                int at = index;
                blackjack.scoreOf(seated.get(index)).ifPresent(score ->
                        scores.add(new SeatScore(at, score, 0, 0, 0)));
            }
        }
        TableSettings options = table.settings();
        int optionBits = (blackjack.game().bankerPlaysByHand(options) ? BANK_BY_HAND : 0)
                | (blackjack.game().bankerWinsTies(options) ? BANK_WINS_TIES : 0);
        return new FormatView(blackjack.format().ordinal(), blackjack.playsForMoney(), bankerSeat, tourney,
                List.copyOf(scores), optionBits);
    }

    private static int bitsOf(List<Action> actions) {
        int moves = 0;
        for (Action action : actions) {
            moves |= switch (action) {
                case BlackjackAction.Hit hit -> HIT;
                case BlackjackAction.Stand stand -> STAND;
                case BlackjackAction.DoubleDown doubleDown -> DOUBLE;
                case BlackjackAction.Split split -> SPLIT;
                case BlackjackAction.Insurance insurance -> INSURE;
                default -> 0;
            };
        }
        return moves;
    }

    /** Resolved here rather than on the client, for the reason roulette gives. */
    private static String nameOf(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) {
            return player.getGameProfile().getName();
        }
        if (server.getProfileCache() == null) {
            return "?";
        }
        return server.getProfileCache().get(playerId).map(GameProfile::getName).orElse("?");
    }

    public static void handleOnClient(BlackjackStatePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                com.github.arrivedbog593.tablegames.client.ClientBlackjackState.accept(payload));
    }
}
