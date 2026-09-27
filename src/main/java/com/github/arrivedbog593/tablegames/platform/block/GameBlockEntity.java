package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Payout;
import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import com.github.arrivedbog593.tablegames.engine.table.SeatChange;
import com.github.arrivedbog593.tablegames.engine.table.TableAccess;
import com.github.arrivedbog593.tablegames.engine.table.TableOccupancy;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.engine.table.TableStacks;
import com.github.arrivedbog593.tablegames.platform.economy.BuyInMessages;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.RealFunds;
import com.github.arrivedbog593.tablegames.platform.economy.SandboxFunds;
import com.github.arrivedbog593.tablegames.platform.economy.TableFunds;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/**
 * A block somebody plays a game at: everything a game needs from the world
 * that is not the game itself.
 * <p>
 * Two blocks sit on this. A table hosts whichever game was chosen for it and
 * seats up to as many players as that game takes. A slot machine is one game
 * forever and seats one. What they share is everything else, and it is most
 * of what is here: who may set the block up, how it is set up, who is
 * standing at it, what each of them has reserved against their balance, what
 * the house has riding on it, and how any of that reaches a client.
 * <p>
 * The split is drawn where the differences actually are. A subclass says
 * which game it hosts and how the block looks; it does not re-answer who may
 * touch it or how a stack is held, because those answers cannot differ
 * without one of the two blocks being wrong.
 * <p>
 * Seats, stacks and settings are the engine's, not this class's. What lives
 * here is the part that needs a world: block entities, packets, permissions
 * against a live server, and the shared registries.
 * <p>
 * Rounds are never saved. A round is a live thing with people standing at it;
 * resuming one across a restart, with everyone logged off and their stakes
 * half committed, is worse than starting again. Only the assigned game and
 * how the block is set up survive a reload — everybody comes back standing.
 * <p>
 * Security note: what a client is told is built per player, and every action
 * a client can send is revalidated here. Nothing goes over the wire wholesale.
 */
public abstract class GameBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY_OWNER = "owner";
    private static final String KEY_TRUSTED = "trusted";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_CONFIGURED = "configured";

    /** Seats a table has before a game says otherwise. */
    private static final int UNASSIGNED_SEATS = 1;

    /**
     * How this table has been set up, as plain numbers keyed by setting id.
     * <p>
     * Persisted, like the game it hosts. The block stores the numbers and the
     * game gives them meaning, so a table gains blinds or rule toggles by the
     * game declaring them and nothing here changing.
     * <p>
     * Values belonging to games this table is not currently hosting are kept
     * rather than dropped. A table switched from roulette to blackjack and
     * back finds the limits it used to post still there.
     */
    private TableSettings settings = TableSettings.empty();

    /**
     * A configuration accepted while something was at stake, waiting for the
     * round to end. Null when there is none.
     * <p>
     * A round settles under the rules in force when its wagers were taken,
     * so tightening them under a live bet would have the engine refuse a
     * stake this table already accepted.
     */
    private TableSettings pendingSettings;

    /**
     * Whether somebody has gone through the game's settings since it was
     * assigned.
     * <p>
     * Choosing a game is not enough to play it. Nobody may open or sit at the
     * table until whoever set it up has confirmed what it takes, so a table
     * never goes live on defaults nobody looked at.
     */
    private boolean configured;

    /**
     * Who this table belongs to and who else may set it up.
     * <p>
     * Per table rather than per player, because the thing being shared is a
     * block, not a friendship. A table in a shared base is the whole reason
     * this exists: whoever put it down should not have to be online for the
     * others to switch from poker to Uno. The rules themselves live in the
     * engine, where they can be tested without a running server.
     */
    private final TableAccess access = new TableAccess();

    private TableOccupancy occupancy = new TableOccupancy(UNASSIGNED_SEATS);

    /**
     * What each seated player bought in with, for games that ask for it.
     * <p>
     * Reserved rather than taken: published as the player's commitment here,
     * so no other table or counter may spend it, while the credits themselves
     * stay in the balance and move only at settlement.
     */
    private final TableStacks stacks = new TableStacks();

    /** The game being played here, rebuilt empty whenever the game is set or loaded. */
    private TableRuntime runtime = TableRuntime.IdleRuntime.INSTANCE;

    /**
     * Where this block's money comes from: the real economy, or a test
     * table's pretend one.
     * <p>
     * Not saved. A test table that came back from a restart would be a table
     * nobody remembered making, taking wagers that look real and are not, so
     * every block loads real and has to be made a test table again.
     */
    private TableFunds funds = RealFunds.INSTANCE;

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

    protected GameBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // --- The game being hosted -------------------------------------------------

    /** The game played at this block, if it has one and it is still registered. */
    public abstract Optional<Game> game();

    /**
     * Whether this block could be set to host something else.
     * <p>
     * False here, because a block built around one game is the ordinary case
     * and a table is the exception. The setup screen asks so that it knows
     * whether a way back to the list of games is a button or a lie: a slot
     * machine has no list to go back to, and the one it used to draw sent a
     * request the server quietly dropped.
     */
    public boolean mayChangeGame() {
        return false;
    }


    /**
     * The id of the game hosted here, or empty text when none is.
     * <p>
     * Derived from the game by default. A block that stores an id of its own
     * overrides this, because a stored id outlives the game being registered
     * and "what this block says it is" and "what it can find" are different
     * questions.
     */
    public String gameId() {
        return game().map(Game::id).orElse("");
    }

    /** The live game, whatever it is. */
    public TableRuntime runtime() {
        return runtime;
    }

    // --- Test tables ------------------------------------------------------------------

    /** Where this block's money comes from and goes to. */
    public TableFunds funds() {
        return funds;
    }

    /** Whether this is a test table, playing with pretend money. */
    public boolean isTestTable() {
        return funds.isSandbox();
    }

    /**
     * Turns this block into a test table with a pretend bank and a pretend
     * balance for every player, or changes the figures of one that already is.
     *
     * @return why it cannot, or null when done
     */
    public Component makeTestTable(long bank, long startingBalance) {
        Component refusal = switchRefusal();
        if (refusal != null) {
            return refusal;
        }
        switchFunds(new SandboxFunds(bank, startingBalance));
        return null;
    }

    /**
     * Puts this block back on the real economy, and forgets any result it
     * was told to land.
     *
     * @return why it cannot, or null when done
     */
    public Component makeRealTable() {
        if (!isTestTable()) {
            return null;
        }
        Component refusal = switchRefusal();
        if (refusal != null) {
            return refusal;
        }
        runtime.rig(null);
        switchFunds(RealFunds.INSTANCE);
        return null;
    }

    /**
     * Only an empty table changes where its money comes from.
     * <p>
     * A stack is a reservation against one economy and a wager a promise in
     * it; carried across to the other they would be reservations against
     * credits that do not exist there, or pretend credits settled as real.
     */
    private Component switchRefusal() {
        if (!occupancy.seats().isEmpty() || !stacks.players().isEmpty() || runtime.hasLiveStakes()) {
            return Component.translatable("tablegames.debug.table_not_empty");
        }
        return null;
    }

    private void switchFunds(TableFunds next) {
        releaseCommitments();
        funds = next;
        publishCommitments();
        markDirty();
    }

    /** Whether a game is assigned and its settings were confirmed, so it may be played. */
    public boolean isConfigured() {
        return configured && game().isPresent();
    }

    /** Whose table this is if anybody's. */
    public Optional<UUID> owner() {
        return access.owner();
    }

    /** Records who placed it. Called from the block when it is placed. */
    public void claim(UUID owner) {
        access.claim(owner);
        setChanged();
    }

    /** Everyone the owner has shared this table with. */
    public Set<UUID> trusted() {
        return access.trusted();
    }

    /**
     * Lets somebody else configure this table.
     *
     * @return false if they were already on the list, or it is full
     */
    public boolean trust(UUID playerId) {
        if (!access.trust(playerId)) {
            return false;
        }
        setChanged();
        return true;
    }

    /** @return false if they were not on the list to begin with */
    public boolean untrust(UUID playerId) {
        if (!access.untrust(playerId)) {
            return false;
        }
        setChanged();
        return true;
    }

    /** Whether this player decides who may configure the table. */
    public boolean mayShare(ServerPlayer player) {
        return access.mayShare(player.getUUID(), hasAuthorityOverTables(player));
    }

    /**
     * Whether this player may set up the game this table hosts: its settings,
     * and going back to the list of games.
     * <p>
     * A game against the house is authority's alone, whoever owns the block;
     * see {@link TableAccess#mayConfigure(UUID, boolean, boolean)}. An empty
     * table, or one hosting a game between players, is the owner's and their
     * guests' as well.
     */
    public boolean mayConfigure(ServerPlayer player) {
        return access.mayConfigure(player.getUUID(), hasAuthorityOverTables(player),
                game().map(Game::isHouseBanked).orElse(false));
    }

    /** Whether this player may put this particular game on the table. */
    public boolean mayHost(ServerPlayer player, Game game) {
        return access.mayConfigure(player.getUUID(), hasAuthorityOverTables(player),
                game.isHouseBanked());
    }

    /**
     * Whether this player may take the table back to hosting nothing.
     * <p>
     * Wider than {@link #mayConfigure}: the owner of a table somebody put a
     * house game on can still empty it. Nothing of the house's is at stake in
     * a table that hosts nothing, and without this the owner's only way out
     * would be breaking the block.
     */
    public boolean mayClear(ServerPlayer player) {
        return access.mayConfigure(player.getUUID(), hasAuthorityOverTables(player));
    }

    /**
     * Why this player may not set up the table, or null when they may.
     * <p>
     * Distinguishes the owner of a house game from a stranger, because
     * "this is not your table" is wrong, and baffling, said to its owner.
     */
    public Component configureRefusal(ServerPlayer player) {
        if (mayConfigure(player)) {
            return null;
        }
        return Component.translatable(mayClear(player)
                ? "tablegames.table.house_game_staff_only"
                : "tablegames.command.table.not_yours");
    }

    /**
     * Operators, and staff at a rank that reaches every table.
     * <p>
     * A moderator is not enough. That rank is authority over the economy —
     * the shop, the conversion values, the bankroll — and a table is a block
     * somebody put down, which may as easily be in their own base as in the
     * casino.
     */
    private static boolean hasAuthorityOverTables(ServerPlayer player) {
        return player.hasPermissions(2)
                || EconomyData.get(player.server).managesEveryTable(player.getUUID());
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
        runtime.votesChanged();
        markDirty();
    }

    /**
     * Takes a seat, bringing a stack when the game asks for one.
     *
     * @param amount what to buy in with; ignored by a game without a buy-in
     * @return why not, or null when seated
     */
    public Component sit(ServerPlayer player, long amount) {
        UUID playerId = player.getUUID();
        if (!isConfigured()) {
            return Component.translatable(SeatChange.NOT_AT_TABLE.translationKey());
        }
        Optional<BuyIn> buyIn = buyIn();
        if (buyIn.isPresent() && !occupancy.isSeated(playerId)) {
            Component refused = refusalFor(buyIn.get(), amount, 0, available(player), true);
            if (refused != null) {
                return refused;
            }
        }
        SeatChange change = occupancy.sit(playerId, phase());
        if (!change.changed()) {
            return Component.translatable(change.translationKey());
        }
        if (buyIn.isPresent()) {
            stacks.add(playerId, amount);
            publishCommitments();
        }
        markDirty();
        return null;
    }

    /**
     * Adds to a seated player's stack, between rounds only.
     * <p>
     * Not while a round is counting down: topping up is for somebody who ran
     * short, not a way to size a wager after seeing how the table bet.
     *
     * @return why not, or null when added
     */
    public Component rebuy(ServerPlayer player, long amount) {
        UUID playerId = player.getUUID();
        Optional<BuyIn> buyIn = buyIn();
        if (buyIn.isEmpty() || !stacks.holds(playerId)) {
            return Component.translatable("tablegames.seat.must_be_seated");
        }
        if (phase().isCountingDown()) {
            return Component.translatable("tablegames.buyin.between_rounds");
        }
        // Not joining: they are already in the seat, so the price of
        // admission has been paid and no longer applies — including to a
        // player who has just lost the lot and wants one more go.
        Component refused = refusalFor(buyIn.get(), amount, stacks.stackOf(playerId),
                available(player), false);
        if (refused != null) {
            return refused;
        }
        stacks.add(playerId, amount);
        publishCommitments();
        markDirty();
        return null;
    }

    /** What a buy-in rule says about this much, with the figures filled in. */
    private static Component refusalFor(BuyIn buyIn, long amount, long stack,
                                        long available, boolean joining) {
        return buyIn.problemWith(amount, stack, available, joining)
                .map(problem -> BuyInMessages.describe(buyIn, problem, stack, available))
                .orElse(null);
    }

    /**
     * What this player could still reserve here: their balance, less what
     * other tables hold and less the stack they already have at this one.
     */
    public long available(ServerPlayer player) {
        UUID playerId = player.getUUID();
        long balance = funds.balanceOf(player.server, playerId);
        long elsewhere = funds.stakes().committedElsewhere(playerId, commitmentKey());
        return Math.max(0L, balance - elsewhere - stacks.stackOf(playerId));
    }

    /** The buy-in this table asks for, or empty when players bet from their balance. */
    public Optional<BuyIn> buyIn() {
        return game().flatMap(assigned -> assigned.buyIn(settings));
    }

    /** What this player has at the table, zero without a stack. */
    public long stackOf(UUID playerId) {
        return stacks.stackOf(playerId);
    }

    /**
     * Gives up a seat and goes back to watching.
     * <p>
     * Wagers already down come back with them: nothing has moved yet, so
     * dropping them is the whole refund. This is refused during the lockout,
     * which is what stops it being a way out of a losing round. Their stack
     * goes too, which is what frees it for spending elsewhere.
     */
    public SeatChange stand(UUID playerId) {
        SeatChange change = occupancy.stand(playerId, phase());
        if (change.changed()) {
            loseSeat(playerId);
            runtime.votesChanged();
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
        runtime.votesChanged();
        markDirty();
        return true;
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

    // --- The round, whatever the game -------------------------------------------

    public RoundPhase phase() {
        return runtime.phase();
    }

    public int secondsRemaining() {
        return runtime.secondsRemaining();
    }

    /** What this player has riding on the round in progress. */
    public long wageredBy(UUID playerId) {
        return runtime.wageredBy(playerId);
    }

    /**
     * How this table is set up, counting a change still waiting for the round
     * to end. What anybody editing the table should start from.
     */
    public TableSettings settings() {
        return pendingSettings != null ? pendingSettings : settings;
    }

    /** The settings the round in progress is played under. */
    TableSettings activeSettings() {
        return settings;
    }

    /** Whether the last accepted configuration is waiting for the round to end. */
    public boolean hasPendingSettings() {
        return pendingSettings != null;
    }

    /**
     * Takes a whole configuration at once, or refuses it whole.
     * <p>
     * With something at stake it is held until the round ends instead of
     * applied; see {@link #pendingSettings}. Either way it counts as the
     * table having been set up, which is what lets it be played.
     *
     * @return the problem the assigned game found, or empty when it was accepted
     */
    public Optional<String> applySettings(TableSettings proposed) {
        Objects.requireNonNull(proposed, "proposed");
        Optional<String> problem = game().flatMap(game -> game.settingsProblem(proposed));
        if (problem.isPresent()) {
            return problem;
        }
        if (runtime.hasLiveStakes()) {
            this.pendingSettings = proposed;
        } else {
            this.settings = proposed;
            this.pendingSettings = null;
        }
        this.configured = game().isPresent();
        setChanged();
        markDirty();
        return Optional.empty();
    }

    private void applyPendingSettings() {
        if (pendingSettings != null) {
            settings = pendingSettings;
            pendingSettings = null;
            setChanged();
        }
    }

    // --- What the runtime calls back into -------------------------------------

    /** Whether every seated player has said they are done. */
    boolean allSeatedReady() {
        return occupancy.allSeatedReady();
    }

    /** Takes back a player's ready vote, for when they changed their wager. */
    void withdrawVote(UUID playerId) {
        occupancy.setReady(playerId, false);
    }

    /**
     * Moves every stack by what a settled round paid or took, so each still
     * says how much of its player's balance this table may take.
     */
    void settleStacks(Outcome outcome) {
        for (Payout payout : outcome.payouts()) {
            stacks.settle(payout.playerId(), payout.delta());
        }
    }

    /** Closes the books on a round: votes cleared, participation recorded. */
    void endRound(List<UUID> participants) {
        // The round is over either way, so the house is no longer exposed to
        // it, and the other tables get their share of the bankroll back. The
        // players get their credits back too: whatever was riding on this
        // round has either been paid or been lost by now. What stays reserved
        // is each stack, which outlives the round.
        publishCommitments();
        applyPendingSettings();
        occupancy.clearReady();
        occupancy.noteRoundEnded(participants);
        markDirty();
    }

    /**
     * Ends any round in progress without settling.
     * <p>
     * Nothing to refund: wagers only become real credits at settlement, so
     * dropping them is the refund. Stacks stay, with the seats they belong to.
     */
    public void abandon() {
        runtime.abandon();
        publishCommitments();
        applyPendingSettings();
        occupancy.clearReady();
        markDirty();
    }

    // --- Commitments ------------------------------------------------------------

    /**
     * How this table is named in the shared registries.
     * <p>
     * Public because the state packet needs it too: telling a player what
     * they may still wager here means asking what they have committed
     * everywhere else, and "everywhere else" is defined relative to this key.
     */
    public String commitmentKey() {
        String dimension = level == null ? "?" : level.dimension().location().toString();
        return dimension + "@" + worldPosition.toShortString();
    }

    /**
     * Republishes everything this table is holding: the house's worst case,
     * and what each player has reserved or riding on it.
     * <p>
     * Recomputed from the round and the stacks rather than adjusted in steps,
     * and called from every path that changes either. A commitment that
     * outlives its round or its seat is worse than one published twice — it
     * narrows the other tables' limits and freezes a player's credits with
     * nothing left to release it — and recomputing from what is actually at
     * the table cannot drift the way an increment can.
     * <p>
     * A player's commitment is their stack when they have one, since that is
     * what they set aside, and otherwise the chips they have down.
     */
    void publishCommitments() {
        long worstCase = runtime.worstCaseHouseCost();
        if (worstCase > 0) {
            funds.commitExposure(commitmentKey(), worstCase);
        } else {
            funds.releaseExposure(commitmentKey());
        }

        // Cleared first, so that a player who took every chip back, or stood
        // up, is released rather than left at whatever they had before.
        funds.releaseStakes(commitmentKey());
        Set<UUID> holders = new LinkedHashSet<>(stacks.players());
        holders.addAll(occupancy.seats());
        for (UUID playerId : holders) {
            long held = Math.max(stacks.stackOf(playerId), runtime.wageredBy(playerId));
            funds.commitStake(commitmentKey(), playerId, held);
        }
    }

    /**
     * Hands part of a player's stack back without taking their seat.
     * <p>
     * Nothing moves in the balance: a stack is a reservation, not custody,
     * so shrinking it simply frees credits the player already owned to be
     * spent elsewhere. That is what makes it safe for a game to offer a
     * partial cash-out — a slot machine paying out its prize bank while the
     * player stays at the cabinet — without a second path through
     * settlement that could disagree with the first.
     *
     * @param amount how much to release; clamped to what is actually held
     * @return what was released, which is zero when there was nothing
     */
    long releaseFromStack(UUID playerId, long amount) {
        long held = stacks.stackOf(playerId);
        long freed = Math.min(Math.max(0L, amount), held);
        if (freed == 0) {
            return 0;
        }
        stacks.settle(playerId, -freed);
        publishCommitments();
        markDirty();
        return freed;
    }

    /** Lets go of the bankroll and of everybody's credits at once. */
    private void releaseCommitments() {
        funds.releaseExposure(commitmentKey());
        funds.releaseStakes(commitmentKey());
    }

    /** Whatever a player had here goes with their seat: wagers and stack alike. */
    private void loseSeat(UUID playerId) {
        runtime.seatLost(playerId);
        stacks.remove(playerId);
        publishCommitments();
        markDirty();
    }

    /**
     * Clears the block of whatever it was hosting and builds the round and
     * the seats for what it hosts now.
     * <p>
     * Called by a subclass once it has changed its answer to {@link #game()}.
     * Everything belonging to the old game goes: wagers were never real
     * credits, seats belonged to a seat count that may have just changed, and
     * stacks belong to seats. The new game always arrives unconfigured, so
     * nobody ever plays one on defaults nobody looked at.
     */
    protected void resetForNewGame() {
        abandon();
        occupancy.clear();
        stacks.clear();
        this.configured = false;
        rebuildForGame();
        publishCommitments();
        setChanged();
        markDirty();
    }

    // --- Ticking ------------------------------------------------------------------

    /**
     * One server tick of whatever is going on here.
     * <p>
     * Called by each block's own ticker rather than registered here, because
     * a block entity ticker is typed to its own class.
     */
    protected void tick() {
        evictTheAbsent();
        runtime.tick();

        if (stateDirty) {
            stateDirty = false;
            broadcastState();
        }
    }

    private void evictTheAbsent() {
        // Their chips come back with them, exactly as if they had stood up.
        // The eviction is held until a phase that allows it, so this can
        // never fire mid-lockout on a live stake.
        for (UUID playerId : occupancy.tickAbsences(phase())) {
            loseSeat(playerId);
        }
    }

    // --- Talking to clients ---------------------------------------------------------

    /**
     * Sends this block's update to every client in range, carrying whatever
     * the game has to tell the room — see {@link TableRuntime#writeNews}.
     * <p>
     * Different from {@link #markDirty}, which reaches only the people with
     * the game open. This reaches everyone near enough to hear the block.
     */
    public void announce() {
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /** Marks the table as needing a snapshot on the next tick. */
    public void markDirty() {
        stateDirty = true;
    }

    /** What one viewer is allowed to see of the game here, or null for nothing. */
    public CustomPacketPayload stateFor(MinecraftServer server, UUID viewer) {
        return runtime.stateFor(server, viewer);
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
            CustomPacketPayload state = stateFor(server, viewer);
            if (state != null) {
                PacketDistributor.sendToPlayer(player, state);
            }
        }
    }

    void tellViewers(Component message) {
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

    /**
     * Reads whatever says which game this block hosts, before anything that
     * depends on knowing.
     * <p>
     * Its own step because the answer arrives differently: a table reads an
     * id it stored, and a machine already knows. Settings are validated
     * against the game and the round is built for it, so both have to come
     * after this and neither can be left to a subclass to remember.
     */
    protected void readIdentity(@NotNull CompoundTag tag) {
    }

    /** Writes whatever {@link #readIdentity} will need back. */
    protected void writeIdentity(@NotNull CompoundTag tag) {
    }

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        readIdentity(tag);
        readAccess(tag);
        this.settings = readSettings(tag);
        this.configured = tag.getBoolean(KEY_CONFIGURED);
        rebuildForGame();
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        writeIdentity(tag);
        writeAccess(tag);
        writeSettings(tag);
        tag.putBoolean(KEY_CONFIGURED, configured);
    }

    /**
     * Builds the round and the seats for whatever game is hosted now.
     * <p>
     * Both are derived rather than stored: a game that gained or lost a seat
     * between versions gets the seat count it asks for today, and a round is
     * never resumed anyway.
     */
    protected void rebuildForGame() {
        Game assigned = game().orElse(null);
        this.runtime = TableRuntime.forGame(assigned, this);
        this.occupancy = new TableOccupancy(
                assigned == null ? UNASSIGNED_SEATS : Math.max(1, assigned.maxPlayers()),
                absenceSeconds());
    }

    /**
     * How long a seat here survives with nobody looking at it.
     * <p>
     * A table's default is generous, and should be: a seat is one of several,
     * a round may be live, and losing your place at poker because you tabbed
     * out is a worse mistake than holding a chair empty for a minute. A block
     * with one seat has the opposite problem — the seat is the whole block,
     * and every second it is held is a second nobody else can play at all.
     */
    protected int absenceSeconds() {
        return TableOccupancy.DEFAULT_ABSENCE_SECONDS;
    }

    /**
     * Reads how a table was set up.
     * <p>
     * Every value is taken as a number without judging it here: the specs
     * that say what is legal belong to the game, which may not be the one
     * that stored them. Reading validates each value against its own spec,
     * and a set the game refuses outright is dropped rather than kept, so a
     * hand edited or corrupted tag cannot leave a table posting limits that
     * would refuse every wager on half its layout.
     */
    private TableSettings readSettings(CompoundTag tag) {
        if (!tag.contains(KEY_SETTINGS)) {
            return TableSettings.empty();
        }
        CompoundTag stored = tag.getCompound(KEY_SETTINGS);
        Map<String, Long> values = new LinkedHashMap<>();
        for (String key : stored.getAllKeys()) {
            values.put(key, stored.getLong(key));
        }
        TableSettings read = TableSettings.of(values);
        Optional<String> problem = game().flatMap(game -> game.settingsProblem(read));
        if (problem.isPresent()) {
            LOGGER.warn("[TableGames] A table had unusable settings stored ({}); "
                    + "falling back to the defaults.", problem.get());
            return TableSettings.empty();
        }
        return read;
    }

    private void readAccess(CompoundTag tag) {
        UUID storedOwner = tag.hasUUID(KEY_OWNER) ? tag.getUUID(KEY_OWNER) : null;
        List<UUID> guests = new ArrayList<>();
        ListTag stored = tag.getList(KEY_TRUSTED, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < stored.size(); i++) {
            int[] raw = stored.getIntArray(i);
            if (raw.length == 4) {
                guests.add(UUIDUtil.uuidFromIntArray(raw));
            }
        }
        access.restore(storedOwner, guests);
    }

    private void writeAccess(CompoundTag tag) {
        access.owner().ifPresent(owner -> tag.putUUID(KEY_OWNER, owner));
        Set<UUID> guests = access.trusted();
        if (guests.isEmpty()) {
            return;
        }
        ListTag stored = new ListTag();
        for (UUID guest : guests) {
            stored.add(new IntArrayTag(UUIDUtil.uuidToIntArray(guest)));
        }
        tag.put(KEY_TRUSTED, stored);
    }

    /** Writes a pending change as if applied: rounds are not saved, so it would be by reload. */
    private void writeSettings(CompoundTag tag) {
        TableSettings toWrite = settings();
        if (toWrite.isEmpty()) {
            return;
        }
        CompoundTag stored = new CompoundTag();
        toWrite.values().forEach(stored::putLong);
        tag.put(KEY_SETTINGS, stored);
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
        releaseCommitments();
    }
}
