package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.economycore.api.CreditFormat;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackGame;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackLimits;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackTournament;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.platform.house.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A blackjack tournament table.
 * <p>
 * Between tournaments the table is a lobby: players sit, which sets their
 * entry aside when it is played for money, and say they are ready. When
 * every seat is ready and at least two can play, the tournament starts with
 * whoever that is, and nobody joins it after. See {@link BlackjackTournament}
 * for how it is won.
 * <p>
 * Each hand is bet in tournament chips during a short window. A player says
 * their wager is final rather than voting the table ready, and the hand is
 * dealt as soon as everybody still in has; whoever has not when the clock
 * runs out bets the minimum, as at a casino tournament. The dealer stakes
 * nothing, so nothing here asks the house anything.
 * <p>
 * The whole tournament is {@link RoundPhase#LOCKED} to the table: nobody
 * stands up from it, and a change to the settings waits for it to end.
 * Leaving is withdrawing, which forfeits the entry to the pot. Credits move
 * once, when it is decided.
 */
final class BlackjackTournamentTable extends BlackjackTable {

    /** For a wager in chips that cost nothing: shorter than at the house. */
    private static final int BET_SECONDS = 20;

    private static final int LOCK_SECONDS = 2;

    /** Each hand's result, before the next is bet. */
    private static final int HAND_RESULT_SECONDS = 5;

    /** The final standings, before the table goes back to being a lobby. */
    private static final int FINAL_SECONDS = 20;

    private final BettingWindow window = new BettingWindow(BET_SECONDS, LOCK_SECONDS, HAND_RESULT_SECONDS);

    /** The tournament being played or whose standings are on show; null in the lobby. */
    private BlackjackTournament tournament;

    /** Whether the tournament is decided and its standings are on show. */
    private boolean over;

    private int finalTicks;

    /** This hand's wagers, in tournament chips. */
    private final Map<UUID, Long> bets = new LinkedHashMap<>();

    /** Who has said their wager is final. */
    private final Set<UUID> confirmed = new LinkedHashSet<>();

    BlackjackTournamentTable(GameBlockEntity table, BlackjackGame game) {
        super(table, game);
    }

    // --- The round --------------------------------------------------------------------------

    @Override
    public RoundPhase phase() {
        if (tournament == null) {
            return RoundPhase.IDLE;
        }
        return over ? RoundPhase.RESULT : RoundPhase.LOCKED;
    }

    @Override
    public int secondsRemaining() {
        if (tournament == null) {
            return 0;
        }
        if (over) {
            return (finalTicks + TICKS - 1) / TICKS;
        }
        return handLive() ? handSecondsRemaining() : window.secondsRemaining();
    }

    @Override
    public boolean isBettingOpen() {
        return tournament != null && !over && hand().isEmpty() && window.phase() == RoundPhase.OPEN;
    }

    @Override
    public long betOf(UUID playerId) {
        return bets.getOrDefault(playerId, 0L);
    }

    @Override
    public boolean hasConfirmed(UUID playerId) {
        return confirmed.contains(playerId);
    }

    @Override
    public Optional<BlackjackTournament> tournament() {
        return Optional.ofNullable(tournament);
    }

    @Override
    public boolean tournamentDecided() {
        return tournament != null && over;
    }

    /** Tournament chips are not credits: nothing is ever riding on a hand here but the entry. */
    @Override
    public long wageredBy(UUID playerId) {
        return 0;
    }

    @Override
    public boolean hasLiveStakes() {
        return tournament != null && !over;
    }

    @Override
    public long worstCaseHouseCost() {
        return 0;
    }

    @Override
    public void seatLost(UUID playerId) {
        // Only between tournaments, which hold nothing of anybody's.
    }

    @Override
    public void votesChanged() {
        if (tournament == null) {
            guarded(this::tryToStart);
        }
    }

    @Override
    protected boolean isGone(UUID playerId) {
        return super.isGone(playerId) || (tournament != null && tournament.hasWithdrawn(playerId));
    }

    // --- Limits -----------------------------------------------------------------------------

    @Override
    public long currentMaximum(MinecraftServer server, UUID playerId) {
        long chips = tournament == null ? 0 : tournament.chipsOf(playerId);
        return Math.min(limits().ceiling(), chips);
    }

    // --- Starting it --------------------------------------------------------------------------

    /**
     * Starts the tournament once every seat is ready, with whoever of them
     * is here and has their entry set aside.
     */
    private void tryToStart() {
        if (!table.allSeatedReady()) {
            return;
        }
        TableSettings settings = table.activeSettings();
        long entry = game.entryFrom(settings);
        List<UUID> entrants = new ArrayList<>();
        for (UUID seated : table.seatedPlayers()) {
            if (table.isPresent(seated) && !table.isAway(seated) && table.isReady(seated)
                    && table.stackOf(seated) >= entry) {
                entrants.add(seated);
            }
        }
        if (entrants.size() < 2) {
            return;
        }
        tournament = new BlackjackTournament(entrants, entry, game.startingChipsFrom(settings),
                game.handsFrom(settings), game.prizeSplitFrom(settings));
        over = false;
        for (UUID entrant : entrants) {
            table.withdrawVote(entrant);
        }
        play(ModSounds.BLACKJACK_SHUFFLE.get(), 0.8f);
        openBetting();
    }

    private void openBetting() {
        bets.clear();
        confirmed.clear();
        window.reset();
        window.start();
        table.markDirty();
    }

    // --- Wagers -----------------------------------------------------------------------------

    @Override
    public Component placeBet(ServerPlayer player, long amount) {
        UUID playerId = player.getUUID();
        if (tournament == null || !tournament.isIn(playerId)) {
            return Component.translatable("tablegames.blackjack.not_in_tournament");
        }
        if (!isBettingOpen()) {
            return Component.translatable("tablegames.blackjack.betting_closed");
        }
        long chips = tournament.chipsOf(playerId);
        long total = betOf(playerId) + amount;
        if (total > chips) {
            return Component.translatable("tablegames.reject.blackjack.not_enough_chips",
                    CreditFormat.of(Math.max(0, chips - betOf(playerId))));
        }
        BlackjackLimits limits = limits();
        if (total > limits.ceiling()) {
            return Component.translatable("tablegames.reject.above_maximum_bet",
                    CreditFormat.of(limits.ceiling()),
                    CreditFormat.of(Math.max(0, limits.ceiling() - betOf(playerId))));
        }
        bets.put(playerId, total);
        confirmed.remove(playerId);
        play(ModSounds.ROULETTE_CHIP.get(), 0.7f);
        table.markDirty();
        return null;
    }

    @Override
    public boolean clearBet(UUID playerId) {
        if (!isBettingOpen() || bets.remove(playerId) == null) {
            return false;
        }
        confirmed.remove(playerId);
        table.markDirty();
        return true;
    }

    /**
     * Makes a wager final. Under the minimum it is refused, unless it is
     * every chip the player has left: going all in for less is always allowed.
     */
    @Override
    public Component confirmBet(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (tournament == null || !tournament.isIn(playerId)) {
            return Component.translatable("tablegames.blackjack.not_in_tournament");
        }
        if (!isBettingOpen()) {
            return Component.translatable("tablegames.blackjack.betting_closed");
        }
        long bet = betOf(playerId);
        long minimum = limits().minimum();
        if (bet < minimum && bet < tournament.chipsOf(playerId)) {
            return Component.translatable("tablegames.reject.below_minimum_bet", CreditFormat.of(minimum));
        }
        confirmed.add(playerId);
        callIfEverybodyIsIn();
        table.markDirty();
        return null;
    }

    private void callIfEverybodyIsIn() {
        if (confirmed.containsAll(tournament.stillIn())) {
            window.callNow();
        }
    }

    /** Gives up a place; the entry stays in the pot. */
    @Override
    public Component withdraw(ServerPlayer player) {
        UUID playerId = player.getUUID();
        if (tournament == null || over || !tournament.isIn(playerId)) {
            return Component.translatable("tablegames.blackjack.not_in_tournament");
        }
        tournament.withdraw(playerId);
        bets.remove(playerId);
        confirmed.remove(playerId);
        if (!handLive()) {
            if (tournament.isOver()) {
                window.reset();
                guarded(this::finish);
            } else if (isBettingOpen()) {
                callIfEverybodyIsIn();
            }
        }
        table.markDirty();
        return null;
    }

    // --- Ticking ------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (tournament == null) {
            return;
        }
        if (over) {
            finalTicks--;
            if (finalTicks <= 0) {
                backToLobby();
            } else if (finalTicks % TICKS == 0) {
                table.markDirty();
            }
            return;
        }
        if (handLive()) {
            guarded(this::tickHand);
            return;
        }
        switch (window.tick()) {
            case SPIN -> guarded(this::deal);
            case RESULT_CLEARED -> guarded(this::nextHand);
            case LOCKED, SECOND_ELAPSED -> table.markDirty();
            case NONE -> {
            }
        }
    }

    /**
     * Deals everybody still in, for what they put down, or for the minimum —
     * or what they have left, when that is less — if they put down nothing.
     */
    private void deal() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        long minimum = limits().minimum();
        List<UUID> players = tournament.stillIn();
        Map<UUID, Long> opening = new LinkedHashMap<>();
        Map<UUID, Long> credits = new LinkedHashMap<>();
        for (UUID player : players) {
            long chips = tournament.chipsOf(player);
            long bet = bets.getOrDefault(player, 0L);
            if (bet < minimum) {
                bet = Math.min(minimum, chips);
            }
            opening.put(player, bet);
            credits.put(player, chips);
        }
        bets.clear();
        confirmed.clear();
        tournament.startHand();
        dealHand(level, players, opening, credits, null);
    }

    /** Tournament chips change hands; credits do not, until the end. */
    @Override
    protected boolean applyOutcome(MinecraftServer server, Outcome outcome) {
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (Payout payout : outcome.payouts()) {
            deltas.put(payout.playerId(), payout.delta());
        }
        tournament.recordHand(deltas);
        return true;
    }

    @Override
    protected void handOver(List<UUID> players, Outcome outcome) {
        window.showResult();
        table.markDirty();
    }

    private void nextHand() {
        clearHand();
        if (tournament.isOver()) {
            finish();
        } else {
            openBetting();
        }
    }

    /**
     * Decides the tournament and pays it: every entrant's prize less their
     * entry, all at once. A tournament played for fun only shows who won.
     */
    private void finish() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        Outcome outcome = tournament.outcome();
        if (tournament.entry() > 0) {
            OutcomeSettler.Result settled = table.funds().settle(level.getServer(), game, outcome,
                    "tournament at " + table.getBlockPos().toShortString());
            if (settled.applied()) {
                table.settleStacks(outcome);
            } else {
                table.tellViewers(Component.translatable(settled.reasonKey()));
                table.tellViewers(Component.translatable("tablegames.settle.refunded"));
            }
        }
        play(ModSounds.ROULETTE_PAYOUT.get(), 0.9f);
        over = true;
        finalTicks = FINAL_SECONDS * TICKS;
        table.endRound(tournament.entrants());
    }

    private void backToLobby() {
        tournament = null;
        over = false;
        clearHand();
        table.markDirty();
    }

    @Override
    public void abandon() {
        super.abandon();
        tournament = null;
        over = false;
        bets.clear();
        confirmed.clear();
        window.reset();
    }
}
