package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackBanker;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackGame;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRules;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackStage;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.platform.house.OutcomeSettler;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blackjack with the bank passing round the players: the kitchen-table game.
 * <p>
 * Nobody bets: every seat plays for the one wager the table set, against
 * whichever player holds the bank that hand, and the bank moves one seat on
 * every hand. A player who could not pay the table if it all went wrong is
 * passed over for the bank — see {@link BlackjackBanker} — and the next seat
 * takes it. The house is in none of it, so the hand is zero-sum and nothing
 * here asks the bankroll anything.
 * <p>
 * Between hands a short clock runs, cut short when every seat is ready, so
 * that a table of friends deals as fast as it likes and a seat that wandered
 * off does not hold it up. Somebody away from the screen sits the hand out.
 * <p>
 * Played for fun, nobody's credits are touched: every seat plays from an
 * endless pile and the felt keeps a running score instead.
 */
final class BlackjackBankerTable extends BlackjackTable {

    /** Long enough to see the last hand's result and say ready; short enough not to drag. */
    private static final int BETWEEN_HANDS_SECONDS = 10;

    private static final int LOCK_SECONDS = 2;

    /**
     * What a seat plays from when nothing is at stake: more than any hand
     * could need, and far enough below the ceiling of a long that adding up
     * a table of them cannot overflow.
     */
    private static final long ENDLESS = Long.MAX_VALUE / 64;

    private final BettingWindow window =
            new BettingWindow(BETWEEN_HANDS_SECONDS, LOCK_SECONDS, BettingWindow.DEFAULT_RESULT_SECONDS);

    /** Where the bank goes next, as a position in the seat list. */
    private int nextBank;

    /** What each seat has won or lost so far, at a table played for fun. */
    private final Map<UUID, Long> scores = new HashMap<>();

    BlackjackBankerTable(GameBlockEntity table, BlackjackGame game) {
        super(table, game);
    }

    // --- The round --------------------------------------------------------------------------

    @Override
    public RoundPhase phase() {
        return handLive() ? RoundPhase.LOCKED : window.phase();
    }

    @Override
    public int secondsRemaining() {
        return handLive() ? handSecondsRemaining() : window.secondsRemaining();
    }

    /** Nobody chooses a wager here. */
    @Override
    public boolean isBettingOpen() {
        return false;
    }

    @Override
    public long currentMaximum(MinecraftServer server, UUID playerId) {
        return limits().minimum();
    }

    @Override
    public Optional<Long> scoreOf(UUID playerId) {
        return playsForMoney() ? Optional.empty() : Optional.of(scores.getOrDefault(playerId, 0L));
    }

    /** The bank's next holder, shown between hands so nobody is surprised by it. */
    @Override
    public Optional<UUID> upcomingBanker() {
        return chooseBanker(eligible()).map(BlackjackBanker::playerId);
    }

    @Override
    public long wageredBy(UUID playerId) {
        if (!playsForMoney() || !handLive() || hand().get().stage() == BlackjackStage.SETTLED) {
            return 0;
        }
        return hand().get().wageredBy(playerId);
    }

    @Override
    public boolean hasLiveStakes() {
        return handLive();
    }

    /** The bank is a player's, never the house's. */
    @Override
    public long worstCaseHouseCost() {
        return 0;
    }

    @Override
    public void seatLost(UUID playerId) {
        scores.remove(playerId);
    }

    @Override
    public void votesChanged() {
        if (table.allSeatedReady() && window.isRunning()) {
            window.callNow();
        }
    }

    // --- Ticking ------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (handLive()) {
            guarded(this::tickHand);
            return;
        }
        // The clock runs whenever there is a game to be had.
        if (window.phase() == RoundPhase.IDLE && hand().isEmpty() && eligible().size() >= 2) {
            window.start();
            table.markDirty();
        }
        switch (window.tick()) {
            case SPIN -> guarded(this::deal);
            case RESULT_CLEARED -> {
                clearHand();
                table.markDirty();
            }
            case LOCKED, SECOND_ELAPSED -> table.markDirty();
            case NONE -> {
            }
        }
    }

    /** Seats that can play the next hand: here, and able to cover the wager. */
    private List<UUID> eligible() {
        long bet = limits().minimum();
        List<UUID> players = new ArrayList<>();
        for (UUID seated : table.seatedPlayers()) {
            if (!table.isAway(seated) && creditsOf(seated) >= bet) {
                players.add(seated);
            }
        }
        return players;
    }

    private long creditsOf(UUID playerId) {
        return playsForMoney() ? table.stackOf(playerId) : ENDLESS;
    }

    /**
     * The bank for a hand between these players: the next seat round from
     * where it last was that could pay every other seat's wager at its best
     * payout. Doubles and splits are then only allowed while it still can.
     */
    private Optional<BlackjackBanker> chooseBanker(List<UUID> players) {
        if (players.size() < 2) {
            return Optional.empty();
        }
        List<UUID> seats = table.seatedPlayers();
        TableSettings settings = table.activeSettings();
        BlackjackRules rules = rules();
        long bet = limits().minimum();
        long cover = rules.worstCaseOn(bet) * (players.size() - 1);
        for (int step = 0; step < seats.size(); step++) {
            UUID candidate = seats.get(Math.floorMod(nextBank + step, seats.size()));
            if (players.contains(candidate) && creditsOf(candidate) >= cover) {
                return Optional.of(new BlackjackBanker(candidate, creditsOf(candidate),
                        game.bankerPlaysByHand(settings), game.bankerWinsTies(settings)));
            }
        }
        return Optional.empty();
    }

    private void deal() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        List<UUID> eligible = eligible();
        Optional<BlackjackBanker> bank = chooseBanker(eligible);
        if (bank.isEmpty()) {
            if (eligible.size() >= 2) {
                table.tellViewers(Component.translatable("tablegames.blackjack.nobody_can_bank"));
            }
            table.endRound(List.of());
            return;
        }
        BlackjackBanker banker = bank.get();
        nextBank = table.seatedPlayers().indexOf(banker.playerId()) + 1;

        long bet = limits().minimum();
        List<UUID> players = new ArrayList<>();
        Map<UUID, Long> opening = new LinkedHashMap<>();
        Map<UUID, Long> credits = new LinkedHashMap<>();
        for (UUID player : eligible) {
            if (!player.equals(banker.playerId())) {
                players.add(player);
                opening.put(player, bet);
                credits.put(player, creditsOf(player));
            }
        }
        dealHand(level, players, opening, credits, banker);
    }

    @Override
    protected boolean applyOutcome(MinecraftServer server, Outcome outcome) {
        if (!playsForMoney()) {
            outcome.payouts().forEach(payout -> scores.merge(payout.playerId(), payout.delta(), Long::sum));
            return true;
        }
        OutcomeSettler.Result settled = table.funds().settle(
                server, game, outcome, "at " + table.getBlockPos().toShortString());
        if (!settled.applied()) {
            table.tellViewers(Component.translatable(settled.reasonKey()));
            table.tellViewers(Component.translatable("tablegames.settle.refunded"));
            return false;
        }
        table.settleStacks(outcome);
        return true;
    }

    @Override
    protected void handOver(List<UUID> players, Outcome outcome) {
        if (outcome != null) {
            window.showResult();
        }
        table.endRound(players);
    }

    @Override
    public void abandon() {
        super.abandon();
        window.reset();
    }
}
