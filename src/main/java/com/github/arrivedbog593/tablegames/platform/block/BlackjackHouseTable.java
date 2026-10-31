package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.economycore.api.CreditFormat;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackGame;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackLimits;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackRules;
import com.github.arrivedbog593.tablegames.engine.games.blackjack.BlackjackStage;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.house.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Blackjack against the house.
 * <p>
 * Betting is roulette's: whoever is seated puts a wager in their spot while a
 * clock runs, and the ready button cuts it short. Then the hand, paid from
 * and to the house's bank, every wager checked against what the bankroll can
 * cover before it is taken.
 */
final class BlackjackHouseTable extends BlackjackTable {

    private final BettingWindow window = new BettingWindow();

    /** Opening wagers, in the order they went down, while betting is open. */
    private final Map<UUID, Long> bets = new LinkedHashMap<>();

    BlackjackHouseTable(GameBlockEntity table, BlackjackGame game) {
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

    @Override
    public boolean isBettingOpen() {
        return hand().isEmpty() && window.phase().acceptsBets();
    }

    @Override
    public long betOf(UUID playerId) {
        return bets.getOrDefault(playerId, 0L);
    }

    @Override
    public long wageredBy(UUID playerId) {
        if (handLive()) {
            return hand().get().stage() == BlackjackStage.SETTLED ? 0 : hand().get().wageredBy(playerId);
        }
        return betOf(playerId);
    }

    @Override
    public boolean hasLiveStakes() {
        return !bets.isEmpty() || handLive();
    }

    @Override
    public long worstCaseHouseCost() {
        if (handLive()) {
            return hand().get().worstCaseHouseCost();
        }
        BlackjackRules rules = rules();
        long worst = 0;
        for (long bet : bets.values()) {
            worst += rules.worstCaseOn(bet);
        }
        return worst;
    }

    @Override
    public void seatLost(UUID playerId) {
        // Only ever between hands: the table refuses to let anybody stand,
        // and holds evictions, while one is live.
        bets.remove(playerId);
    }

    @Override
    public void votesChanged() {
        if (table.allSeatedReady() && window.isRunning()) {
            window.callNow();
        }
    }

    // --- Limits -----------------------------------------------------------------------------

    /**
     * The largest opening wager the table will take right now: its own posted
     * maximum, or what the bankroll can cover at two to one — a blackjack, or
     * a double — whichever is less.
     */
    @Override
    public long currentMaximum(MinecraftServer server, UUID playerId) {
        return Math.min(table.funds().tableMaximum(server, game, 2), limits().ceiling());
    }

    @Override
    protected boolean houseCovers(MinecraftServer server, long worstCase) {
        return table.funds().withinExposure(server, game, table.commitmentKey(), worstCase);
    }

    // --- Wagers -----------------------------------------------------------------------------

    @Override
    public Component placeBet(ServerPlayer player, long amount) {
        UUID playerId = player.getUUID();
        if (!table.isSeated(playerId)) {
            return Component.translatable("tablegames.seat.must_be_seated");
        }
        if (!isBettingOpen()) {
            return Component.translatable("tablegames.blackjack.betting_closed");
        }
        MinecraftServer server = player.server;
        if (!table.funds().canOpen(server, game)) {
            return Component.translatable("tablegames.roulette.house_closed");
        }
        long total = betOf(playerId) + amount;
        BlackjackLimits limits = limits();
        if (total < limits.minimum()) {
            return Component.translatable("tablegames.reject.below_minimum_bet",
                    CreditFormat.of(limits.minimum()));
        }
        long maximum = currentMaximum(server, playerId);
        if (total > maximum) {
            return Component.translatable("tablegames.reject.above_maximum_bet",
                    CreditFormat.of(maximum), CreditFormat.of(Math.max(0, maximum - betOf(playerId))));
        }
        long stack = table.stackOf(playerId);
        if (total > stack) {
            return Component.translatable("tablegames.reject.insufficient_stack",
                    CreditFormat.of(Math.max(0, stack - betOf(playerId))));
        }
        long worstCase = worstCaseHouseCost() - rules().worstCaseOn(betOf(playerId))
                + rules().worstCaseOn(total);
        if (!houseCovers(server, worstCase)) {
            return Component.translatable("tablegames.reject.house_exposed");
        }

        bets.put(playerId, total);
        play(ModSounds.ROULETTE_CHIP.get(), 0.7f);
        table.publishCommitments();
        table.withdrawVote(playerId);
        window.start();
        table.markDirty();
        return null;
    }

    @Override
    public boolean clearBet(UUID playerId) {
        if (!isBettingOpen() || bets.remove(playerId) == null) {
            return false;
        }
        table.publishCommitments();
        table.markDirty();
        return true;
    }

    // --- Ticking ------------------------------------------------------------------------------

    @Override
    public void tick() {
        if (handLive()) {
            guarded(this::tickHand);
            return;
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

    /**
     * Closes betting and deals.
     * <p>
     * Seated players with a wager down are dealt in, in seat order. Each seat
     * plays with its stack: a double or a split can only ever put down what
     * the player brought.
     */
    private void deal() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        List<UUID> players = new ArrayList<>();
        Map<UUID, Long> opening = new LinkedHashMap<>();
        Map<UUID, Long> credits = new LinkedHashMap<>();
        for (UUID seated : table.seatedPlayers()) {
            if (bets.containsKey(seated)) {
                players.add(seated);
                opening.put(seated, bets.get(seated));
                credits.put(seated, table.stackOf(seated));
            }
        }
        bets.clear();
        if (players.isEmpty()) {
            table.endRound(players);
            return;
        }
        dealHand(level, players, opening, credits, null);
    }

    @Override
    protected boolean applyOutcome(MinecraftServer server, Outcome outcome) {
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
        bets.clear();
        window.reset();
    }
}
