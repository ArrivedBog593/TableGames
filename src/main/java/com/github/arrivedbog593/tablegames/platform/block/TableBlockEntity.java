package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetLimits;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteAction;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteSession;
import com.github.arrivedbog593.tablegames.engine.session.ActionResult;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.BettingWindow;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.engine.table.SeatChange;
import com.github.arrivedbog593.tablegames.engine.table.TableOccupancy;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The state of one table: which game it hosts, who is at it, and the round in
 * progress.
 * <p>
 * Opening a table makes you a spectator. Sitting down is a separate act, and
 * the seats are counted, which is what stops a ninth player from reaching a
 * session built for eight — that used to be an uncaught exception inside this
 * very tick. Only seated players may wager.
 * <p>
 * Rounds are never saved. A round is a live thing with people standing at it;
 * resuming one across a restart, with everyone logged off and their stakes
 * half committed, is worse than starting again. Only the assigned game and
 * whether it is pinned survive a reload — everybody comes back standing.
 * <p>
 * Security note: what a client is told is built per player, and every action
 * a client can send is revalidated here. Nothing goes over the wire wholesale.
 */
public class TableBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY_GAME = "game";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_LIMITS = "limits";
    private static final String KEY_INSIDE_MIN = "inside_min";
    private static final String KEY_INSIDE_MAX = "inside_max";
    private static final String KEY_OUTSIDE_MIN = "outside_min";
    private static final String KEY_OUTSIDE_MAX = "outside_max";

    /** Seats a table has before a game says otherwise. */
    private static final int UNASSIGNED_SEATS = 1;

    private String gameId = "";

    /**
     * What this table chooses to accept, over and above what the house can
     * afford. Persisted: it is a property of the table, like its game.
     */
    private BetLimits limits = BetLimits.DEFAULT;

    /**
     * Whether the assigned game is fixed.
     * <p>
     * For a server that has laid out a casino and does not want a visitor
     * turning the poker table into Uno halfway through a hand. Pinning and
     * unpinning need operator rights; configuring an unpinned table does not,
     * so a player's own table in their own base stays theirs to set up.
     */
    private boolean pinned;

    private final BettingWindow window = new BettingWindow();

    private TableOccupancy occupancy = new TableOccupancy(UNASSIGNED_SEATS);

    /** Wagers taken this round, in the order they were placed. */
    private final Map<UUID, List<RouletteBet>> bets = new LinkedHashMap<>();

    private Pocket lastResult;

    /**
     * Whether clients need a fresh snapshot.
     * <p>
     * Set instead of broadcasting on the spot and flushed once per tick.
     * Sending on every accepted wager meant a client could make the server
     * serialize the whole table to every viewer as fast as it could send
     * packets; coalescing turns that into one packet a tick no matter how
     * hard anybody tries.
     */
    private boolean stateDirty;

    public TableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TABLE.get(), pos, state);
    }

    // --- Assigned game -------------------------------------------------------

    /** The game this table hosts if one is assigned and still registered. */
    public Optional<Game> game() {
        return gameId.isEmpty() ? Optional.empty() : Games.registry().get(gameId);
    }

    public String gameId() {
        return gameId;
    }

    public boolean isPinned() {
        return pinned;
    }

    /** Pins or unpins the assigned game. Callers must check operator rights. */
    public void setPinned(boolean pinned) {
        this.pinned = pinned;
        setChanged();
        markDirty();
    }

    /**
     * Assigns a game, dropping any round in progress and standing everyone up.
     * <p>
     * Changing the game under live wagers would leave players staked into
     * rules that no longer apply and seated into a table whose seat count may
     * have just changed underneath them.
     *
     * @return false if the table is pinned and nothing was changed
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean setGame(Game game) {
        if (pinned) {
            return false;
        }
        abandon();
        occupancy.clear();
        this.gameId = game == null ? "" : game.id();
        this.occupancy = new TableOccupancy(
                game == null ? UNASSIGNED_SEATS : Math.max(1, game.maxPlayers()));
        setChanged();
        updateVariant(game == null ? TableVariant.BLANK : Games.variantOf(game));
        markDirty();
        return true;
    }

    private void updateVariant(TableVariant variant) {
        if (level == null) {
            return;
        }
        BlockState state = getBlockState();
        if (state.getValue(TableBlock.VARIANT) != variant) {
            level.setBlock(worldPosition, state.setValue(TableBlock.VARIANT, variant), 3);
        }
    }

    // --- Who is at the table ---------------------------------------------------

    /** Somebody opened the table. They watch until they choose to sit. */
    public void arrive(UUID playerId) {
        occupancy.arrive(playerId);
        markDirty();
    }

    /**
     * Somebody closed the screen, crashed, or dropped their connection.
     * <p>
     * A seated player keeps their seat and starts an absence clock rather
     * than losing it outright: there is no way to tell a misclick from an
     * exit, and taking the seat away is the worst mistake of the two. Their
     * wagers stay on the layout and settle without them, win or lose.
     */
    public void leaveScreen(UUID playerId) {
        occupancy.markAbsent(playerId);
        // Absent players are counted ready, which can be the last vote the
        // table was waiting on.
        callIfUnanimous();
        markDirty();
    }

    /** Takes a seat if the game allows it and the round is not locked. */
    public SeatChange sit(UUID playerId) {
        if (game().isEmpty()) {
            return SeatChange.NOT_AT_TABLE;
        }
        SeatChange change = occupancy.sit(playerId, phase());
        if (change.changed()) {
            markDirty();
        }
        return change;
    }

    /**
     * Gives up a seat and goes back to watching.
     * <p>
     * Wagers already down come back with them: nothing has moved yet, so
     * dropping them from the map is the whole refund. This is refused during
     * the lockout, which is what stops it being a way out of a losing round.
     */
    public SeatChange stand(UUID playerId) {
        SeatChange change = occupancy.stand(playerId, phase());
        if (change.changed()) {
            bets.remove(playerId);
            refreshExposure();
            callIfUnanimous();
            markDirty();
        }
        return change;
    }

    /** Declares a seated player finished betting or changes their mind back. */
    public boolean setReady(UUID playerId, boolean ready) {
        if (!phase().acceptsBets()) {
            return false;
        }
        if (!occupancy.setReady(playerId, ready)) {
            return false;
        }
        callIfUnanimous();
        markDirty();
        return true;
    }

    /**
     * Cuts the window short when every seated player has said they are done.
     * <p>
     * Only ever shortens. The clock keeps running underneath, so one player
     * who never presses anything delays nobody past the thirty seconds.
     */
    private void callIfUnanimous() {
        if (occupancy.allSeatedReady() && window.isRunning()) {
            window.callNow();
        }
    }

    public List<UUID> seatedPlayers() {
        return occupancy.seats();
    }

    public int spectatorCount() {
        return occupancy.spectatorCount();
    }

    public int maxSeats() {
        return occupancy.maxSeats();
    }

    public boolean isSeated(UUID playerId) {
        return occupancy.isSeated(playerId);
    }

    public boolean isReady(UUID playerId) {
        return occupancy.isReady(playerId);
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isPresent(UUID playerId) {
        return occupancy.isPresent(playerId);
    }

    public OptionalInt seatIndexOf(UUID playerId) {
        return occupancy.seatIndexOf(playerId);
    }

    // --- The round --------------------------------------------------------------

    public RoundPhase phase() {
        return window.phase();
    }

    /** Whether a wager may be placed or withdrawn right now. */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isBettingOpen() {
        return window.phase().acceptsBets();
    }

    public int secondsRemaining() {
        return window.secondsRemaining();
    }

    public Optional<Pocket> lastResult() {
        return window.phase() == RoundPhase.RESULT
                ? Optional.ofNullable(lastResult)
                : Optional.empty();
    }

    /** What this player has on the layout right now. */
    public List<RouletteBet> betsOf(UUID playerId) {
        return List.copyOf(bets.getOrDefault(playerId, List.of()));
    }

    public long wageredBy(UUID playerId) {
        long total = 0;
        for (RouletteBet bet : betsOf(playerId)) {
            total += bet.amount();
        }
        return total;
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
        Optional<Game> assigned = game();
        if (assigned.isEmpty()) {
            return 0;
        }
        long derived = OutcomeSettler.tableMaximum(server, assigned.get(),
                type.payoutRatio());
        return Math.min(derived, limits.maximumFor(type));
    }

    /** The smallest wager this table will take on a bet of this type. */
    public long effectiveMinimum(BetType type) {
        return limits.minimumFor(type);
    }

    public BetLimits limits() {
        return limits;
    }

    public void setLimits(BetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        setChanged();
        markDirty();
    }

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
        Optional<Game> assigned = game();
        if (assigned.isEmpty() || !(assigned.get() instanceof RouletteGame roulette)) {
            return Component.translatable("tablegames.table.unassigned");
        }
        UUID playerId = player.getUUID();
        if (!occupancy.isSeated(playerId)) {
            return Component.translatable("tablegames.seat.must_be_seated");
        }
        if (!isBettingOpen()) {
            return Component.translatable("tablegames.roulette.betting_closed");
        }
        MinecraftServer server = player.server;

        if (!OutcomeSettler.canOpen(server, roulette)) {
            return Component.translatable("tablegames.roulette.house_closed");
        }
        if (bets.getOrDefault(playerId, List.of()).size()
                >= RouletteStatePayload.MAX_BETS_ON_WIRE) {
            return Component.translatable("tablegames.reject.too_many_bets");
        }
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
        long mine = stakedOn(playerId, bet);
        if (mine + bet.amount() > limits.maximumFor(bet.type())) {
            return Component.translatable("tablegames.reject.above_maximum_bet",
                    CreditFormat.of(limits.maximumFor(bet.type())),
                    CreditFormat.of(Math.max(0, limits.maximumFor(bet.type()) - mine)));
        }
        // The bankroll's limit is table-wide because that is what the house
        // actually has to cover. A straight-up pocket paying 35:1 costs the
        // house the same whether one player or eight put the credits there.
        long derived = OutcomeSettler.tableMaximum(server, roulette, bet.type().payoutRatio());
        long onTable = stakedOn(null, bet);
        if (onTable + bet.amount() > derived) {
            return Component.translatable("tablegames.reject.position_full",
                    CreditFormat.of(Math.max(0, derived - onTable)));
        }
        if (bet.type().requiresTarget()
                && !roulette.wheel().pockets().contains(bet.target())) {
            return Component.translatable("tablegames.reject.no_such_pocket");
        }

        long balance = CreditStorage.get(server).balanceOf(playerId);
        if (wageredBy(playerId) + bet.amount() > balance) {
            return Component.translatable("tablegames.reject.insufficient_credits");
        }

        // The last limit, and the only one that knows about the other tables.
        // Both checks above are about this table alone; the bankroll is
        // shared, so what every table together stands to lose has to fit
        // inside it as well.
        List<RouletteBet> proposed = new ArrayList<>(allBets());
        proposed.add(bet);
        long worstCase = roulette.wheel().worstCaseHouseCost(proposed);
        if (!OutcomeSettler.withinExposure(server, roulette, exposureKey(), worstCase)) {
            return Component.translatable("tablegames.reject.house_exposed");
        }

        bets.computeIfAbsent(playerId, key -> new ArrayList<>()).add(bet);
        OutcomeSettler.commitExposure(exposureKey(), worstCase);
        // Backing a new chip means you are no longer finished, the same way
        // the engine's own session treats it.
        occupancy.setReady(playerId, false);
        window.start();
        markDirty();
        return null;
    }

    /**
     * What is riding on the same position as this wager.
     * <p>
     * The same position means the same bet type and the same target: two chips on red are
     * one stake, a chip on red and one on 17 are two.
     *
     * @param playerId whose chips to count, or null for the whole table
     */
    private long stakedOn(UUID playerId, RouletteBet bet) {
        long total = 0;
        for (Map.Entry<UUID, List<RouletteBet>> entry : bets.entrySet()) {
            if (playerId != null && !playerId.equals(entry.getKey())) {
                continue;
            }
            for (RouletteBet placed : entry.getValue()) {
                if (placed.type() == bet.type()
                        && Objects.equals(placed.target(), bet.target())) {
                    total += placed.amount();
                }
            }
        }
        return total;
    }

    /** Every wager on this table, whoever placed it. */
    private List<RouletteBet> allBets() {
        List<RouletteBet> all = new ArrayList<>();
        for (List<RouletteBet> placed : bets.values()) {
            all.addAll(placed);
        }
        return all;
    }

    /**
     * How this table is identified in the shared exposure registry.
     * <p>
     * Dimension included, because two tables at the same coordinates in the
     * overworld and the nether are different tables and must not share a
     * commitment.
     */
    private String exposureKey() {
        String dimension = level == null ? "?" : level.dimension().location().toString();
        return dimension + "@" + worldPosition.toShortString();
    }

    /** Recomputes and republishes what this table now stands to lose. */
    private void refreshExposure() {
        Optional<Game> assigned = game();
        if (assigned.isEmpty() || !(assigned.get() instanceof RouletteGame roulette)
                || bets.isEmpty()) {
            OutcomeSettler.releaseExposure(exposureKey());
            return;
        }
        OutcomeSettler.commitExposure(exposureKey(),
                roulette.wheel().worstCaseHouseCost(allBets()));
    }

    /** Takes every chip this player has on the layout back off it. */
    public boolean clearBets(UUID playerId) {
        if (!isBettingOpen()) {
            return false;
        }
        if (bets.remove(playerId) == null) {
            return false;
        }
        refreshExposure();
        markDirty();
        return true;
    }

    // --- Ticking ------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  TableBlockEntity table) {
        table.evictTheAbsent();

        switch (table.window.tick()) {
            case SPIN -> table.spin();
            case RESULT_CLEARED -> {
                table.lastResult = null;
                table.markDirty();
            }
            case LOCKED, SECOND_ELAPSED -> table.markDirty();
            case NONE -> {
            }
        }

        if (table.stateDirty) {
            table.stateDirty = false;
            table.broadcastState();
        }
    }

    private void evictTheAbsent() {
        for (UUID playerId : occupancy.tickAbsences(phase())) {
            // Their chips come back with them, exactly as if they had stood
            // up. The eviction is held until a phase that allows it, so this
            // can never fire mid-lockout on a live stake.
            bets.remove(playerId);
            refreshExposure();
            markDirty();
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
                    gameId.isEmpty() ? "an unassigned table" : gameId,
                    worldPosition.toShortString(), failure);
            abandon();
            tellViewers(Component.translatable("tablegames.table.round_failed"));
            markDirty();
        }
    }

    /**
     * Closes betting, runs the wheel, and settles.
     * <p>
     * The engine session is built here rather than held open, with a seat for
     * each player who wagered and their real balance as its stack. Replaying
     * the bets into it gives the tested rules exactly the state they expect.
     * The seat list can never exceed the game's maximum because only seated
     * players are allowed to wager in the first place.
     */
    private void runSpin() {
        if (level == null || level.getServer() == null) {
            return;
        }
        List<UUID> players = new ArrayList<>(bets.keySet());
        if (players.isEmpty()) {
            endRound(players);
            return;
        }

        MinecraftServer server = level.getServer();
        Optional<Game> assigned = game();
        if (assigned.isEmpty() || !(assigned.get() instanceof RouletteGame roulette)) {
            bets.clear();
            endRound(players);
            return;
        }

        CreditStorage storage = CreditStorage.get(server);
        List<Seat> seats = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            seats.add(Seat.forPlayer(i, players.get(i), storage.balanceOf(players.get(i))));
        }

        // RandomSource is Minecraft's own interface and does not implement
        // RandomGenerator, which the engine takes. Seeding a plain Random from
        // the level keeps the wheel tied to the world's randomness without
        // dragging a Minecraft type into the engine's signature.
        RandomGenerator random = new Random(level.random.nextLong());
        RouletteSession session = (RouletteSession) roulette.createSession(seats, random);
        session.begin();
        for (UUID playerId : players) {
            for (RouletteBet bet : bets.get(playerId)) {
                ActionResult result = session.submit(playerId, new RouletteAction.Place(bet));
                if (!result.accepted()) {
                    // The two layers disagree about what is legal. Loud,
                    // because the alternative is a wager that quietly stops
                    // existing between being taken and being paid.
                    LOGGER.error("[TableGames] The engine refused a wager this table had "
                                    + "already accepted, at {}: {}. Player {}, {} on {}.",
                            worldPosition.toShortString(), result.messageKey(),
                            playerId, bet.amount(), bet.type());
                }
            }
        }
        session.spin();

        Pocket result = session.result().orElse(null);
        Outcome outcome = session.outcome().orElse(null);
        bets.clear();

        if (outcome == null) {
            endRound(players);
            return;
        }

        OutcomeSettler.Result settled = OutcomeSettler.settle(
                server, roulette, outcome, "at " + worldPosition.toShortString());

        if (!settled.applied()) {
            // Nothing moved, so nobody lost anything. Say so rather than let
            // the round end in silence.
            tellViewers(Component.translatable(settled.reasonKey()));
            tellViewers(Component.translatable("tablegames.settle.refunded"));
            endRound(players);
            return;
        }

        lastResult = result;
        window.showResult();
        endRound(players);
    }

    /** Closes the books on a round: votes cleared, participation recorded. */
    private void endRound(List<UUID> participants) {
        // The round is over either way, so the house is no longer exposed to
        // it, and the other tables get their share of the bankroll back.
        OutcomeSettler.releaseExposure(exposureKey());
        occupancy.clearReady();
        occupancy.noteRoundEnded(participants);
        markDirty();
    }

    /**
     * Ends any round in progress without settling.
     * <p>
     * Nothing to refund: wagers only become real credits at settlement, so
     * dropping them is the refund.
     */
    public void abandon() {
        OutcomeSettler.releaseExposure(exposureKey());
        bets.clear();
        window.reset();
        occupancy.clearReady();
        lastResult = null;
        markDirty();
    }

    // --- Talking to clients ---------------------------------------------------------

    /** Marks the table as needing a snapshot on the next tick. */
    public void markDirty() {
        stateDirty = true;
    }

    /** Pushes the round's state to everyone with the table open. */
    public void broadcastState() {
        if (level == null || level.getServer() == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        for (UUID viewer : occupancy.everyone()) {
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null) {
                continue;
            }
            PacketDistributor.sendToPlayer(player,
                    RouletteStatePayload.forPlayer(server, this, viewer));
        }
    }

    private void tellViewers(Component message) {
        if (level == null || level.getServer() == null) {
            return;
        }
        for (UUID viewer : occupancy.everyone()) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(viewer);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }

    // --- Persistence -----------------------------------------------------------

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        this.gameId = tag.getString(KEY_GAME);
        this.pinned = tag.getBoolean(KEY_PINNED);
        this.limits = readLimits(tag);
        this.occupancy = new TableOccupancy(game()
                .map(assigned -> Math.max(1, assigned.maxPlayers()))
                .orElse(UNASSIGNED_SEATS));
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(KEY_GAME, gameId);
        tag.putBoolean(KEY_PINNED, pinned);
        writeLimits(tag);
    }

    /**
     * Reads a table's own limits, falling back to the default when the tag
     * predates them. An older table simply had no limits of its own, which is
     * exactly what the default says.
     */
    private static BetLimits readLimits(CompoundTag tag) {
        if (!tag.contains(KEY_LIMITS)) {
            return BetLimits.DEFAULT;
        }
        CompoundTag stored = tag.getCompound(KEY_LIMITS);
        try {
            return new BetLimits(
                    stored.getLong(KEY_INSIDE_MIN),
                    stored.getLong(KEY_INSIDE_MAX),
                    stored.getLong(KEY_OUTSIDE_MIN),
                    stored.getLong(KEY_OUTSIDE_MAX));
        } catch (IllegalArgumentException corrupt) {
            LOGGER.warn("[TableGames] A table had unusable bet limits stored; "
                    + "falling back to the default.", corrupt);
            return BetLimits.DEFAULT;
        }
    }

    private void writeLimits(CompoundTag tag) {
        if (limits.isDefault()) {
            return;
        }
        CompoundTag stored = new CompoundTag();
        stored.putLong(KEY_INSIDE_MIN, limits.insideMinimum());
        stored.putLong(KEY_INSIDE_MAX, limits.insideMaximum());
        stored.putLong(KEY_OUTSIDE_MIN, limits.outsideMinimum());
        stored.putLong(KEY_OUTSIDE_MAX, limits.outsideMaximum());
        tag.put(KEY_LIMITS, stored);
    }

    /**
     * What the client is told on chunk load: only which game the table hosts.
     * Round state travels per player, over the mod's own channel.
     */
    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_GAME, gameId);
        tag.putBoolean(KEY_PINNED, pinned);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /**
     * Frees this table's share of the bankroll when it stops existing.
     * <p>
     * Covers the chunk unloading as well as the block being broken. A
     * commitment left behind by a table nobody can reach would shrink what
     * every other table is allowed to take, with nothing to release it.
     */
    @Override
    public void setRemoved() {
        super.setRemoved();
        OutcomeSettler.releaseExposure(exposureKey());
    }
}
