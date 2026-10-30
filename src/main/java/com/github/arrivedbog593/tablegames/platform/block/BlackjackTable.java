package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.card.Shoe;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackAction;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackBanker;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackFormat;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackGame;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackHand;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackLimits;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRig;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRules;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackSession;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackStage;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackTournament;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.network.BlackjackStatePayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * A blackjack table: the shoe, and one hand at a time.
 * <p>
 * The hand is the same whoever the table plays against, and so is this part:
 * it deals, keeps the clocks, and pays. Turn by turn the way a dealer runs it
 * — insurance for everybody at once under an ace, then each seat in order on
 * its own clock, then the dealer card by card. The rules live in
 * {@link BlackjackSession}; this decides how long everything takes.
 * <p>
 * Who the wagers are against, and where they come from, is each format's:
 * {@link BlackjackHouseTable} against the casino, {@link BlackjackTournamentTable}
 * for tournament chips, {@link BlackjackBankerTable} against a player holding
 * the bank. Each decides when to deal and what to do with what a hand paid.
 * <p>
 * Pacing is part of keeping the result honest on screen. The deal waits long
 * enough for every card to be seen landing, the dealer waits between cards,
 * and the money moves only after the last card is down: a stack that changes
 * before the dealer's card does tells everybody what the card was.
 * <p>
 * The whole hand is {@link RoundPhase#LOCKED} to the table, as a slot machine
 * is while its reels turn: no wagers, no top-ups, and nobody standing up from
 * a live hand. Someone who closes the screen keeps their seat, and their turn
 * is stood for them rather than waited out.
 */
public abstract class BlackjackTable implements TableRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    static final int TICKS = BettingWindow.TICKS_PER_SECOND;

    /** A decision on one hand. Long enough to think, short enough that seven seats is not a wait. */
    static final int TURN_SECONDS = 20;

    /** Everybody answers insurance at once, so it gets less than a turn. */
    static final int INSURANCE_SECONDS = 10;

    /** Between two cards of the deal. */
    static final int DEAL_CARD_TICKS = 5;

    /** Between two of the dealer's cards. */
    static final int DEALER_CARD_TICKS = 16;

    /** From the last card landing to the chips moving. */
    static final int SETTLE_TICKS = 10;

    protected final GameBlockEntity table;
    protected final BlackjackGame game;

    /** Built on the first deal and kept across hands; rebuilt when the deck count changes. */
    private Shoe shoe;

    /** The hand being played, or the one whose result is on show. Null between hands. */
    private BlackjackSession session;

    /** Ticks left on whatever the table is waiting for right now. */
    private int wait;

    /** What {@link #wait} is counting toward, so a move that changes it restarts it. */
    private String waitingOn = "";

    /** Cards of the deal not yet heard landing. */
    private int dealSounds;

    /** Whether the settled outcome has been paid; false while the last card is still landing. */
    private boolean paid;

    /** What each player came out with on the hand on show, for the felt to say. */
    private final Map<UUID, Long> results = new LinkedHashMap<>();

    /** What the next hand has been told to deal, on a test table; null to let it fall. */
    private BlackjackRig rigged;

    BlackjackTable(GameBlockEntity table, BlackjackGame game) {
        this.table = table;
        this.game = game;
    }

    /** The table for a game of this format. */
    static BlackjackTable forGame(GameBlockEntity table, BlackjackGame game) {
        return switch (game.format()) {
            case HOUSE -> new BlackjackHouseTable(table, game);
            case TOURNAMENT -> new BlackjackTournamentTable(table, game);
            case BANKER -> new BlackjackBankerTable(table, game);
        };
    }

    public BlackjackGame game() {
        return game;
    }

    public BlackjackFormat format() {
        return game.format();
    }

    // --- The hand ----------------------------------------------------------------------------

    /** Whether a hand is dealt and not yet paid. */
    protected final boolean handLive() {
        return session != null && (session.stage() != BlackjackStage.SETTLED || !paid);
    }

    /** What the clock says while a hand is live. */
    protected final int handSecondsRemaining() {
        return isOnAClock() ? (wait + TICKS - 1) / TICKS : 0;
    }

    /** Whether the table is waiting on a person rather than on its own pacing. */
    private boolean isOnAClock() {
        if (session == null || dealSounds > 0) {
            return false;
        }
        return session.stage() == BlackjackStage.INSURANCE || session.stage() == BlackjackStage.PLAYERS
                || session.awaitingBanker();
    }

    /** Whether the cards are still going out, so nobody may move yet. */
    public boolean isDealing() {
        return dealSounds > 0;
    }

    /** The hand on the felt, live or on show, if there is one. */
    public Optional<BlackjackSession> hand() {
        return Optional.ofNullable(session);
    }

    /** Whether the hand on the felt has been paid, so its results may be shown. */
    public boolean isPaid() {
        return session != null && paid;
    }

    /** What this player came out with on the hand on show, if they were in it. */
    public Optional<Long> resultOf(UUID playerId) {
        return paid ? Optional.ofNullable(results.get(playerId)) : Optional.empty();
    }

    /** The shoe, for the felt to say how deep into it the table is. Null before the first deal. */
    public Shoe shoe() {
        return shoe;
    }

    public BlackjackLimits limits() {
        return game.limitsFrom(table.activeSettings());
    }

    public BlackjackRules rules() {
        return game.rulesFrom(table.activeSettings());
    }

    /** Whether credits change hands here, or the game is played for nothing. */
    public boolean playsForMoney() {
        return game.playsForMoney(table.activeSettings());
    }

    /** Who holds the bank on the hand on the felt; empty for the house. */
    public Optional<UUID> bankerId() {
        return hand().flatMap(BlackjackSession::banker).map(BlackjackBanker::playerId);
    }

    // --- What each format answers ------------------------------------------------------------

    /** Whether a seat may put down or change an opening wager right now. */
    public abstract boolean isBettingOpen();

    /** This player's opening wager while betting is open. */
    public long betOf(UUID playerId) {
        return 0;
    }

    /** The largest opening wager this player could make right now. */
    public abstract long currentMaximum(MinecraftServer server, UUID playerId);

    /** Who would hold the bank if a hand were dealt now; empty where players never do. */
    public Optional<UUID> upcomingBanker() {
        return Optional.empty();
    }

    /** The tournament being played, if this table plays them and one is on. */
    public Optional<BlackjackTournament> tournament() {
        return Optional.empty();
    }

    /** Whether the tournament on show is decided, its standings final. */
    public boolean tournamentDecided() {
        return false;
    }

    /** What a player has won or lost here so far, at a table played for fun. */
    public Optional<Long> scoreOf(UUID playerId) {
        return Optional.empty();
    }

    /** Whether this player has said their wager is final. */
    public boolean hasConfirmed(UUID playerId) {
        return false;
    }

    // --- Requests ----------------------------------------------------------------------------

    /**
     * Adds to a seated player's opening wager.
     *
     * @return why not, or null when taken
     */
    public Component placeBet(ServerPlayer player, long amount) {
        return Component.translatable("tablegames.blackjack.betting_closed");
    }

    /** Takes a player's opening wager back, while betting is open. */
    public boolean clearBet(UUID playerId) {
        return false;
    }

    /** Says a player's wager is final. Only a tournament asks. */
    public Component confirmBet(ServerPlayer player) {
        return Component.translatable("tablegames.blackjack.betting_closed");
    }

    /** Leaves a tournament before it ends. */
    public Component withdraw(ServerPlayer player) {
        return Component.translatable("tablegames.blackjack.no_tournament");
    }

    /**
     * Plays a move for a player at the hand in progress: a seat's, or the
     * bank's when they hold it.
     *
     * @return why not, or null when made
     */
    public Component act(ServerPlayer player, BlackjackAction move) {
        UUID playerId = player.getUUID();
        if (session == null || !handLive() || dealSounds > 0) {
            return Component.translatable("tablegames.blackjack.no_hand");
        }
        int cardsBefore = cardsOnTheFelt();
        ActionResult result;
        if (bankerId().map(playerId::equals).orElse(false)) {
            result = session.bankerAct(playerId, move);
        } else {
            // A double, a split or insurance puts more down, and the house
            // has to be able to cover it before it is allowed.
            long more = switch (move) {
                case BlackjackAction.DoubleDown doubleDown -> session.nextWagerOnClock();
                case BlackjackAction.Split split -> session.nextWagerOnClock();
                case BlackjackAction.Insurance(boolean take) -> take ? session.insuranceCost(playerId) * 2 : 0;
                default -> 0;
            };
            if (more > 0 && !houseCovers(player.server, session.worstCaseHouseCost() + more)) {
                return Component.translatable("tablegames.reject.house_exposed");
            }
            result = session.submit(playerId, move);
        }
        if (!result.accepted()) {
            return Component.translatable(result.messageKey());
        }
        if (cardsOnTheFelt() > cardsBefore) {
            play(ModSounds.BLACKJACK_CARD.get(), 0.8f);
        }
        if (move instanceof BlackjackAction.Insurance(boolean take) && take) {
            play(ModSounds.ROULETTE_CHIP.get(), 0.7f);
        }
        afterMove();
        return null;
    }

    /** Whether the house could take on this much; only the house's own table ever says no. */
    protected boolean houseCovers(MinecraftServer server, long worstCase) {
        return true;
    }

    private int cardsOnTheFelt() {
        int cards = session.dealerShown().size();
        for (Seat seat : session.seats()) {
            for (BlackjackHand hand : session.handsOf(seat.playerId())) {
                cards += hand.cards().size();
            }
        }
        return cards;
    }

    // --- Ticking ------------------------------------------------------------------------------

    /**
     * Runs a step of the table, guarding the tick against anything the rules
     * throw. Same reasoning as roulette's spin: an exception here stops every
     * block entity behind this one in the chunk, and wagers only become
     * credits at settlement, so dropping the hand is a complete refund.
     */
    protected final void guarded(Runnable step) {
        try {
            step.run();
        } catch (RuntimeException failure) {
            LOGGER.error("[TableGames] A hand of {} at {} failed and was abandoned. "
                            + "No credits were moved.",
                    game.id(), table.getBlockPos().toShortString(), failure);
            table.abandon();
            table.tellViewers(Component.translatable("tablegames.table.round_failed"));
            table.markDirty();
        }
    }

    /** One tick of the hand on the felt. Callers check {@link #handLive()} first. */
    protected final void tickHand() {
        if (wait > 0) {
            wait--;
            if (dealSounds > 0 && wait % DEAL_CARD_TICKS == 0) {
                dealSounds--;
                play(ModSounds.BLACKJACK_CARD.get(), 0.8f);
            }
            if (isOnAClock() && wait % TICKS == 0) {
                table.markDirty();
            }
            // Nobody waits out the clock of a player who is not there.
            if (isOnAClock() && session.stage() == BlackjackStage.PLAYERS
                    && session.currentTurn().map(this::isGone).orElse(false)) {
                wait = 0;
            }
            if (isOnAClock() && session.awaitingBanker() && bankerId().map(this::isGone).orElse(false)) {
                wait = 0;
            }
            if (wait > 0) {
                return;
            }
        }
        dealSounds = 0;

        switch (session.stage()) {
            case INSURANCE -> {
                session.closeInsurance();
                afterMove();
            }
            case PLAYERS -> {
                session.timeOutCurrentTurn();
                afterMove();
            }
            case DEALER -> {
                if (session.awaitingBanker()) {
                    session.bankerTimedOut();
                    afterMove();
                    return;
                }
                boolean wasHidden = session.holeHidden();
                int before = session.dealerShown().size();
                session.dealerStep();
                if (wasHidden) {
                    play(ModSounds.BLACKJACK_FLIP.get(), 0.9f);
                } else if (session.dealerShown().size() > before) {
                    play(ModSounds.BLACKJACK_CARD.get(), 0.8f);
                }
                afterMove();
            }
            case SETTLED -> pay();
        }
    }

    /** Whether a player is not there to play their turn: away from the screen, or out of the game. */
    protected boolean isGone(UUID playerId) {
        return table.isAway(playerId);
    }

    /**
     * Sets the next wait for wherever the hand now stands, and makes sure the
     * table and the room hear about it.
     * <p>
     * A wait restarts only when what it is for has changed: a hit that leaves
     * the same hand on the clock does not hand its player a fresh twenty
     * seconds, or nobody would ever run out. The bank's decision is one clock
     * however many cards it draws, for the same reason.
     */
    private void afterMove() {
        boolean banker = session.awaitingBanker();
        String now = session.stage() + ":" + session.currentTurn().map(UUID::toString).orElse("")
                + ":" + session.currentTurn().map(session::handOnClockIndex).orElse(-1)
                + (banker ? ":banker" : "");
        boolean dealerPacing = session.stage() == BlackjackStage.DEALER && !banker;
        if (!now.equals(waitingOn) || dealerPacing) {
            waitingOn = now;
            wait = switch (session.stage()) {
                case INSURANCE -> session.allInsuranceAnswered() ? 0 : INSURANCE_SECONDS * TICKS;
                case PLAYERS -> TURN_SECONDS * TICKS;
                case DEALER -> banker ? TURN_SECONDS * TICKS : DEALER_CARD_TICKS;
                case SETTLED -> SETTLE_TICKS;
            };
        }
        // Everybody has answered: nothing left to wait for. Never during the
        // deal, which no answer can arrive in anyway.
        if (session.stage() == BlackjackStage.INSURANCE && session.allInsuranceAnswered()
                && dealSounds == 0) {
            wait = 0;
        }
        table.publishCommitments();
        table.markDirty();
    }

    /**
     * Deals a hand: these players, in this order, for these wagers, each
     * playing from what they have here.
     *
     * @param credits what each player can put down on the hand, doubles and
     *                splits included
     * @param banker  who holds the bank, or null for the dealer
     */
    protected final void dealHand(Level level, List<UUID> players, Map<UUID, Long> opening,
                                  Map<UUID, Long> credits, BlackjackBanker banker) {
        BlackjackRules rules = rules();
        Random random = new Random(level.random.nextLong());
        if (shoe == null || shoe.decks() != rules.decks() || shoe.pastCutCard()) {
            shoe = new Shoe(rules.decks(), random);
            play(ModSounds.BLACKJACK_SHUFFLE.get(), 0.8f);
        }
        // A test table told what to deal. Checked again here rather than
        // trusted from when it was set, and spent on this hand either way.
        if (rigged != null && table.isTestTable()) {
            shoe.stack(rigged.cardsFor(players.size()));
        }
        rigged = null;

        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            UUID playerId = players.get(i);
            seats.add(Seat.forPlayer(i, playerId, credits.get(playerId)));
        }
        results.clear();
        paid = false;
        waitingOn = "";

        session = game.createSession(seats, random, rules, shoe, opening, banker);
        session.begin();
        afterMove();
        // However the hand stands, nothing happens until the deal has been
        // seen: two cards to every seat and two to the dealer.
        dealSounds = players.size() * 2 + 2;
        wait = dealSounds * DEAL_CARD_TICKS + Math.max(wait, 0);
    }

    /** Moves the money once the last card is down, and puts the result on show. */
    private void pay() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null || paid) {
            return;
        }
        List<UUID> players = new ArrayList<>(session.seats().stream().map(Seat::playerId).toList());
        bankerId().ifPresent(players::add);
        Outcome outcome = session.outcome().orElse(null);
        paid = true;
        if (outcome == null || !applyOutcome(level.getServer(), outcome)) {
            session = null;
            handOver(players, null);
            return;
        }
        outcome.payouts().forEach(payout -> results.put(payout.playerId(), payout.delta()));
        if (!outcome.winners().isEmpty()) {
            play(ModSounds.ROULETTE_PAYOUT.get(), 0.9f);
        }
        handOver(players, outcome);
    }

    /**
     * Pays a settled hand, in credits or in chips.
     *
     * @return whether it was applied; when not, the hand is dropped, which
     *         refunds it, and the table has said why
     */
    protected abstract boolean applyOutcome(MinecraftServer server, Outcome outcome);

    /**
     * After a hand is paid, or dropped when {@code outcome} is null: the
     * format puts the result on show and closes the round.
     */
    protected abstract void handOver(List<UUID> players, Outcome outcome);

    /** Takes the hand off the felt, once its result has been on show. */
    protected final void clearHand() {
        session = null;
        results.clear();
    }

    protected final void play(SoundEvent sound, float volume) {
        Level level = table.getLevel();
        if (level != null) {
            level.playSound(null, table.getBlockPos(), sound, SoundSource.BLOCKS,
                    volume, 0.95f + level.random.nextFloat() * 0.1f);
        }
    }

    // --- Test tables ---------------------------------------------------------------------------

    @Override
    public List<RigOption> rigOptions() {
        List<RigOption> options = new ArrayList<>();
        for (BlackjackRig rig : BlackjackRig.values()) {
            options.add(new RigOption(rig.id(), Component.translatable(rig.translationKey()), 0x2E6B45));
        }
        return options;
    }

    @Override
    public boolean rig(String optionId) {
        if (optionId == null) {
            rigged = null;
            return true;
        }
        for (BlackjackRig rig : BlackjackRig.values()) {
            if (rig.id().equals(optionId)) {
                rigged = rig;
                return true;
            }
        }
        return false;
    }

    @Override
    public Optional<String> rigged() {
        return Optional.ofNullable(rigged).map(BlackjackRig::id);
    }

    /** Drops the hand on the felt. Formats add whatever else they hold. */
    @Override
    public void abandon() {
        session = null;
        results.clear();
        paid = false;
        wait = 0;
        dealSounds = 0;
        waitingOn = "";
    }

    @Override
    public CustomPacketPayload stateFor(MinecraftServer server, UUID viewer) {
        return BlackjackStatePayload.forPlayer(server, table, this, Objects.requireNonNull(viewer));
    }
}
