package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.games.roulette.BetLimits;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteAction;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteLayout;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteSession;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModSounds;
import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * A roulette wheel at one table: the betting window, the felt, the spin.
 * <p>
 * Many players, one shared round on a clock. Everybody seated puts chips on
 * the same layout while the window runs, the wheel turns once for all of
 * them, and the table settles every wager from that one pocket.
 */
public final class RouletteTable implements TableRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final GameBlockEntity table;
    private final RouletteGame game;

    private final BettingWindow window = new BettingWindow();

    /**
     * Wagers taken this round. The bookkeeping — who has what down, how much
     * rides on a position — is {@link RouletteLayout}'s job, pulled out so it
     * can be tested without a running server.
     */
    private final RouletteLayout layout = new RouletteLayout();

    private Pocket lastResult;

    /** Where the next ball has been told to land, on a test table; null to let it fall. */
    private Pocket rigged;

    /**
     * When the last ball was thrown, in game ticks, and how many pockets it
     * runs past on the strip. Zero before the first.
     * <p>
     * The time rather than a count, so that a runtime rebuilt from nothing —
     * the table given a new game, the chunk reloaded — never looks newer than
     * the spin a client last heard, and never makes a wheel click that nobody
     * spun.
     */
    private long spunAt;
    private int pocketsPassed;

    /** Whether the last throw paid anybody anything, so the table is heard paying out. */
    private boolean paidOut;

    RouletteTable(GameBlockEntity table, RouletteGame game) {
        this.table = table;
        this.game = game;
    }

    public RouletteGame game() {
        return game;
    }

    // --- The round --------------------------------------------------------------

    @Override
    public RoundPhase phase() {
        return window.phase();
    }

    @Override
    public int secondsRemaining() {
        return window.secondsRemaining();
    }

    /** Whether a wager may be placed or withdrawn right now. */
    public boolean isBettingOpen() {
        return window.phase().acceptsBets();
    }

    public Optional<Pocket> lastResult() {
        return window.phase() == RoundPhase.RESULT
                ? Optional.ofNullable(lastResult)
                : Optional.empty();
    }

    /** What this player has on the layout right now. */
    public List<RouletteBet> betsOf(UUID playerId) {
        return layout.betsOf(playerId);
    }

    @Override
    public long wageredBy(UUID playerId) {
        return layout.wageredBy(playerId);
    }

    @Override
    public boolean hasLiveStakes() {
        return !layout.isEmpty();
    }

    @Override
    public long worstCaseHouseCost() {
        return layout.isEmpty() ? 0 : game.wheel().worstCaseHouseCost(layout.allBets());
    }

    @Override
    public void seatLost(UUID playerId) {
        layout.clear(playerId);
    }

    /**
     * Cuts the window short when every seated player has said they are done.
     * <p>
     * Only ever shortens. The clock keeps running underneath, so one player
     * who never presses anything delays nobody past the thirty seconds.
     */
    @Override
    public void votesChanged() {
        if (table.allSeatedReady() && window.isRunning()) {
            window.callNow();
        }
    }

    // --- Limits -----------------------------------------------------------------

    /**
     * The wager limits this table posts, derived from its settings.
     * <p>
     * From the settings in force, not a change still waiting for the round
     * to end: the wagers on the felt were taken under these.
     */
    public BetLimits limits() {
        return game.limitsFrom(table.activeSettings());
    }

    /**
     * The largest wager this table will take right now.
     * <p>
     * Quoted against the straight-up payout, the worst case the table offers,
     * so the figure shown to players is the one that actually binds.
     */
    public long currentTableMaximum(MinecraftServer server) {
        return effectiveMaximum(server, BetType.STRAIGHT_UP);
    }

    /**
     * The largest wager this table will actually take on a bet of this type.
     * <p>
     * The stricter of the two ceilings. The bankroll's is a protection and
     * moves with the balance; the table's is a choice and does not. A table
     * may only ever narrow what the house allows, never widen it — a table
     * promising payouts the bankroll cannot cover would just be a refused
     * settlement waiting to happen.
     */
    public long effectiveMaximum(MinecraftServer server, BetType type) {
        long derived = table.funds().tableMaximum(server, game, type.payoutRatio());
        return Math.min(derived, limits().maximumFor(type));
    }

    /** The smallest wager this table will take on a bet of this type. */
    public long effectiveMinimum(BetType type) {
        return limits().minimumFor(type);
    }

    // --- Wagers -----------------------------------------------------------------

    /**
     * Takes a wager.
     * <p>
     * Only from a seated player, and only against the maximum the engine
     * itself will accept — the platform used to validate against a limit
     * derived from the bankroll while the session validated against its own
     * fixed one, so a wager between the two was taken here and silently
     * dropped at spin time.
     *
     * @return a message explaining a refusal or null when accepted. A whole
     *         component rather than a key, because a refusal that does not
     *         name the limit is useless once a player can type an arbitrary
     *         amount — "too much" is guessable from six fixed chips and is
     *         not from a free-text field.
     */
    public Component placeBet(ServerPlayer player, RouletteBet bet) {
        UUID playerId = player.getUUID();
        if (!table.isSeated(playerId)) {
            return Component.translatable("tablegames.seat.must_be_seated");
        }
        if (!isBettingOpen()) {
            return Component.translatable("tablegames.roulette.betting_closed");
        }
        MinecraftServer server = player.server;

        if (!table.funds().canOpen(server, game)) {
            return Component.translatable("tablegames.roulette.house_closed");
        }
        if (layout.betsOf(playerId).size()
                >= RouletteStatePayload.MAX_BETS_ON_WIRE) {
            return Component.translatable("tablegames.reject.too_many_bets");
        }
        BetLimits limits = limits();
        if (bet.amount() < limits.minimumFor(bet.type())) {
            return Component.translatable("tablegames.reject.below_minimum_bet",
                    CreditFormat.of(limits.minimumFor(bet.type())));
        }
        // Two limits, measuring two different things, both on the position
        // rather than on the chip. Checking one wager at a time made them
        // meaningless: five chips of a thousand on the same number are five
        // legal bets that together commit what one illegal bet would have.
        //
        // The table's own limit is per player, because that is what a posted
        // maximum means to somebody standing at a wheel: the most *you* may
        // put on a number, not the almost everybody together may.
        long mine = layout.stakedOn(playerId, bet);
        if (mine + bet.amount() > limits.maximumFor(bet.type())) {
            return Component.translatable("tablegames.reject.above_maximum_bet",
                    CreditFormat.of(limits.maximumFor(bet.type())),
                    CreditFormat.of(Math.max(0, limits.maximumFor(bet.type()) - mine)));
        }
        // The bankroll's limit is table-wide because that is what the house
        // actually has to cover. A straight-up pocket paying 35:1 costs the
        // house the same whether one player or eight put the credits there.
        long derived = table.funds().tableMaximum(server, game, bet.type().payoutRatio());
        long onTable = layout.stakedOn(null, bet);
        if (onTable + bet.amount() > derived) {
            return Component.translatable("tablegames.reject.position_full",
                    CreditFormat.of(Math.max(0, derived - onTable)));
        }
        if (bet.type().requiresTarget()
                && !game.wheel().pockets().contains(bet.target())) {
            return Component.translatable("tablegames.reject.no_such_pocket");
        }

        if (table.buyIn().isPresent()) {
            // The stack is the whole of what this table may take. The balance
            // behind it was already checked when it was reserved.
            long stack = table.stackOf(playerId);
            if (wageredBy(playerId) + bet.amount() > stack) {
                return Component.translatable("tablegames.reject.insufficient_stack",
                        CreditFormat.of(Math.max(0, stack - wageredBy(playerId))));
            }
        } else {
            // What the balance says, minus what this player has promised to
            // other tables. Their chips on this one are not subtracted: the
            // figure this table publishes replaces its own, so counting it
            // here would stop somebody raising a wager they had already placed.
            long balance = table.funds().balanceOf(server, playerId);
            long elsewhere = table.funds().stakes()
                    .committedElsewhere(playerId, table.commitmentKey());
            if (wageredBy(playerId) + bet.amount() > balance - elsewhere) {
                return Component.translatable("tablegames.reject.insufficient_credits");
            }
        }

        // The last limit, and the only one that knows about the other tables.
        // Both checks above are about this table alone; the bankroll is
        // shared, so what every table together stands to lose has to fit
        // inside it as well.
        List<RouletteBet> proposed = new ArrayList<>(layout.allBets());
        proposed.add(bet);
        long worstCase = game.wheel().worstCaseHouseCost(proposed);
        if (!table.funds().withinExposure(server, game, table.commitmentKey(), worstCase)) {
            return Component.translatable("tablegames.reject.house_exposed");
        }

        layout.place(playerId, bet);
        // Chips on the felt, for the whole table to hear, the bettor included.
        // Played from here rather than worked out on each client because it
        // times nothing: there is no animation for it to keep up with.
        Level level = table.getLevel();
        if (level != null) {
            level.playSound(null, table.getBlockPos(), ModSounds.ROULETTE_CHIP.get(), SoundSource.BLOCKS,
                    0.7f, 0.9f + level.random.nextFloat() * 0.2f);
        }
        table.publishCommitments();
        // Backing a new chip means you are no longer finished, the same way
        // the engine's own session treats it.
        table.withdrawVote(playerId);
        window.start();
        table.markDirty();
        return null;
    }

    /** Takes every chip this player has on the layout back off it. */
    public boolean clearBets(UUID playerId) {
        if (!isBettingOpen()) {
            return false;
        }
        if (!layout.clear(playerId)) {
            return false;
        }
        table.publishCommitments();
        table.markDirty();
        return true;
    }

    // --- Ticking ------------------------------------------------------------------

    @Override
    public void tick() {
        switch (window.tick()) {
            case SPIN -> spin();
            case RESULT_CLEARED -> {
                lastResult = null;
                table.markDirty();
            }
            case LOCKED, SECOND_ELAPSED -> table.markDirty();
            case NONE -> {
            }
        }
    }

    /**
     * Runs the wheel, guarding the tick against anything the rules throw.
     * <p>
     * The engine is written to reject rather than throw, but "written to" is
     * not "proven to", and this runs inside a block entity tick. An unhandled
     * exception here does not fail one table, it kills the ticking of every
     * block entity behind it in the chunk. A bug in a card game must never be
     * able to take the server with it.
     * <p>
     * Wagers become credits only at settlement, so abandoning the round is a
     * complete refund. Nobody loses anything to a failure here except the
     * round.
     */
    private void spin() {
        try {
            runSpin();
        } catch (RuntimeException failure) {
            LOGGER.error("[TableGames] A round of {} at {} failed and was abandoned. "
                            + "No credits were moved.",
                    game.id(), table.getBlockPos().toShortString(), failure);
            table.abandon();
            table.tellViewers(Component.translatable("tablegames.table.round_failed"));
            table.markDirty();
        }
    }

    /**
     * Closes betting, runs the wheel, and settles.
     * <p>
     * The engine session is built here rather than held open, with a seat for
     * each player who wagered and their stack, or balance, as its credits.
     * Replaying the bets into it gives the tested rules exactly the state they
     * expect. The seat list can never exceed the game's maximum because only
     * seated players are allowed to wager in the first place.
     */
    private void runSpin() {
        Level level = table.getLevel();
        if (level == null || level.getServer() == null) {
            return;
        }
        List<UUID> players = layout.players();
        if (players.isEmpty()) {
            table.endRound(players);
            return;
        }
        MinecraftServer server = level.getServer();

        // Each seat plays with what the player brought, or with their balance
        // at a game that asks for no buy-in.
        boolean stacked = table.buyIn().isPresent();
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            UUID playerId = players.get(i);
            seats.add(Seat.forPlayer(i, playerId,
                    stacked ? table.stackOf(playerId) : table.funds().balanceOf(server, playerId)));
        }

        // RandomSource is Minecraft's own interface and does not implement
        // RandomGenerator, which the engine takes. Seeding a plain Random from
        // the level keeps the wheel tied to the world's randomness without
        // dragging a Minecraft type into the engine's signature.
        RandomGenerator random = new Random(level.random.nextLong());
        // Built with this table's limits, not the game's defaults. The rules
        // that replay these wagers have to be the rules that took them, or a
        // stake accepted at the block is refused here and quietly ceases to
        // exist between being taken and being paid.
        RouletteSession session =
                (RouletteSession) game.createSession(seats, random, limits());
        session.begin();
        for (UUID playerId : players) {
            for (RouletteBet bet : layout.betsOf(playerId)) {
                ActionResult result = session.submit(playerId, new RouletteAction.Place(bet));
                if (!result.accepted()) {
                    // The two layers disagree about what is legal. Loud,
                    // because the alternative is a wager that quietly stops
                    // existing between being taken and being paid.
                    LOGGER.error("[TableGames] The engine refused a wager this table had "
                                    + "already accepted, at {}: {}. Player {}, {} on {}.",
                            table.getBlockPos().toShortString(), result.messageKey(),
                            playerId, bet.amount(), bet.type());
                }
            }
        }
        // A test table told where to land. Checked again here rather than
        // trusted from when it was set, and spent on this spin whatever
        // happens to it, so a number asked for once comes up once.
        if (rigged != null && table.isTestTable()) {
            session.rig(rigged);
        }
        rigged = null;
        session.spin();

        Pocket result = session.result().orElse(null);
        Outcome outcome = session.outcome().orElse(null);
        layout.clearAll();

        if (outcome == null) {
            table.endRound(players);
            return;
        }

        OutcomeSettler.Result settled = table.funds().settle(
                server, game, outcome, "at " + table.getBlockPos().toShortString());

        if (!settled.applied()) {
            // Nothing moved, so nobody lost anything. Say so rather than let
            // the round end in silence.
            table.tellViewers(Component.translatable(settled.reasonKey()));
            table.tellViewers(Component.translatable("tablegames.settle.refunded"));
            table.endRound(players);
            return;
        }

        table.settleStacks(outcome);
        lastResult = result;
        window.showResult();
        // The room hears the wheel whether or not anybody has the felt open.
        // Nothing here is news to a spectator: the number is public the moment
        // the ball is out, and the strip is theater over it.
        spunAt = level.getGameTime();
        pocketsPassed = WheelMotion.pocketsToPass(game.wheel().cylinderIndexOf(result),
                game.wheel().cylinder().size());
        paidOut = !outcome.winners().isEmpty();
        table.announce();
        table.endRound(players);
    }

    /** The last throw, for the table to be heard running it down. */
    @Override
    public void writeNews(CompoundTag tag) {
        tag.putLong(TableBlockEntity.KEY_WHEEL_AT, spunAt);
        tag.putInt(TableBlockEntity.KEY_WHEEL_POCKETS, pocketsPassed);
        tag.putBoolean(TableBlockEntity.KEY_WHEEL_PAID, paidOut);
    }

    /** Every pocket on this wheel, in the order the felt lists them, in the felt's colors. */
    @Override
    public List<RigOption> rigOptions() {
        return game.wheel().pockets().stream()
                .map(pocket -> new RigOption(pocket.label(), Component.literal(pocket.label()),
                        switch (pocket.color()) {
                            case RED -> 0xC0302C;
                            case BLACK -> 0x2A2A2A;
                            case GREEN -> 0x1E8040;
                        }))
                .toList();
    }

    @Override
    public boolean rig(String optionId) {
        if (optionId == null) {
            rigged = null;
            return true;
        }
        Optional<Pocket> pocket = game.wheel().pockets().stream()
                .filter(candidate -> candidate.label().equals(optionId))
                .findFirst();
        pocket.ifPresent(found -> rigged = found);
        return pocket.isPresent();
    }

    @Override
    public Optional<String> rigged() {
        return Optional.ofNullable(rigged).map(Pocket::label);
    }

    @Override
    public void abandon() {
        layout.clearAll();
        window.reset();
        lastResult = null;
    }

    @Override
    public CustomPacketPayload stateFor(MinecraftServer server, UUID viewer) {
        return RouletteStatePayload.forPlayer(server, table, this, viewer);
    }
}
