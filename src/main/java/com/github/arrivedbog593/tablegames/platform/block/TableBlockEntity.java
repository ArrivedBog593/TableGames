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
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
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
 * One table: which game it hosts, how it is set up, who owns it, and who is
 * at it.
 * <p>
 * The game itself — its round, its wagers, what it shows — lives in a
 * {@link TableRuntime} built for whatever the table hosts. This class keeps
 * what every game shares and would otherwise copy: the seats, the stacks
 * players bought in with, and the commitments all of that publishes to the
 * shared registries.
 * <p>
 * Opening a table makes you a spectator. Sitting down is a separate act, and
 * the seats are counted, which is what stops a ninth player from reaching a
 * session built for eight — that used to be an uncaught exception inside this
 * very tick. Only seated players may wager.
 * <p>
 * Rounds are never saved. A round is a live thing with people standing at it;
 * resuming one across a restart, with everyone logged off and their stakes
 * half committed, is worse than starting again. Only the assigned game and
 * how the table is set up survive a reload — everybody comes back standing.
 * <p>
 * Security note: what a client is told is built per player, and every action
 * a client can send is revalidated here. Nothing goes over the wire wholesale.
 */
public class TableBlockEntity extends BlockEntity {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String KEY_GAME = "game";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_TRUSTED = "trusted";
    private static final String KEY_SETTINGS = "settings";
    private static final String KEY_CONFIGURED = "configured";

    /** Seats a table has before a game says otherwise. */
    private static final int UNASSIGNED_SEATS = 1;

    private String gameId = "";

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
     * Whether clients need a fresh snapshot.
     * <p>
     * Set instead of broadcasting on the spot and flushed once per tick.
     * Sending on every accepted wager meant a client could make the server
     * serialize the whole table to every viewer as fast as it could send
     * packets; coalescing turns that into one packet a tick no matter how
     * hard anybody tries.
     */
    private boolean stateDirty;

    /**
     * Whether the block's look has been reconciled with the game it hosts.
     * <p>
     * Not persisted: it asks a question about this run, not about the world.
     */
    private boolean variantChecked;

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

    /** The live game, whatever it is. */
    public TableRuntime runtime() {
        return runtime;
    }

    /** The live game when it is a roulette wheel. */
    public Optional<RouletteTable> roulette() {
        return runtime instanceof RouletteTable wheel ? Optional.of(wheel) : Optional.empty();
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

    /** Whether this player may change what the table hosts and what it takes. */
    public boolean mayConfigure(ServerPlayer player) {
        return access.mayConfigure(player.getUUID(), hasAuthorityOverTables(player));
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

    /**
     * Assigns a game, dropping any round in progress and standing everyone up.
     * <p>
     * Changing the game under live wagers would leave players staked into
     * rules that no longer apply and seated into a table whose seat count may
     * have just changed underneath them.
     */
    public void setGame(Game game) {
        abandon();
        occupancy.clear();
        stacks.clear();
        this.gameId = game == null ? "" : game.id();
        this.configured = false;
        this.runtime = TableRuntime.forGame(game, this);
        this.occupancy = new TableOccupancy(
                game == null ? UNASSIGNED_SEATS : Math.max(1, game.maxPlayers()));
        publishCommitments();
        setChanged();
        updateVariant(game == null ? TableVariant.BLANK : Games.variantOf(game));
        markDirty();
    }

    /**
     * Makes the block look like the game it hosts, once, shortly after it
     * starts ticking.
     * <p>
     * The look lives in the block state, and the game lives in the block
     * entity, and there are several ways to move one without the other.
     * Control clicking a configured table in creative copies the entity data
     * and not the state, so the placed copy deals roulette while still
     * wearing the blank gray top. A {@code /setblock} carrying entity data
     * does the same, and so does anything placed from a saved structure.
     * <p>
     * Derived from the game id rather than repaired by hand, because the id
     * is the truth: a look computed from it cannot drift from it, whatever
     * route the block took to get here.
     */
    private void ensureVariant() {
        if (variantChecked) {
            return;
        }
        variantChecked = true;
        updateVariant(game().map(Games::variantOf).orElse(TableVariant.BLANK));
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
            Component refused = refusalFor(buyIn.get(), amount, 0, available(player));
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
        Component refused = refusalFor(buyIn.get(), amount, stacks.stackOf(playerId),
                available(player));
        if (refused != null) {
            return refused;
        }
        stacks.add(playerId, amount);
        publishCommitments();
        markDirty();
        return null;
    }

    /** What a buy-in rule says about this much, with the figures filled in. */
    private static Component refusalFor(BuyIn buyIn, long amount, long stack, long available) {
        return buyIn.problemWith(amount, stack, available)
                .map(problem -> BuyInMessages.describe(buyIn, problem, stack, available))
                .orElse(null);
    }

    /**
     * What this player could still reserve here: their balance, less what
     * other tables hold and less the stack they already have at this one.
     */
    public long available(ServerPlayer player) {
        UUID playerId = player.getUUID();
        long balance = CreditStorage.get(player.server).balanceOf(playerId);
        long elsewhere = OutcomeSettler.stakes().committedElsewhere(playerId, commitmentKey());
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
            OutcomeSettler.commitExposure(commitmentKey(), worstCase);
        } else {
            OutcomeSettler.releaseExposure(commitmentKey());
        }

        // Cleared first, so that a player who took every chip back, or stood
        // up, is released rather than left at whatever they had before.
        OutcomeSettler.releaseStakes(commitmentKey());
        Set<UUID> holders = new LinkedHashSet<>(stacks.players());
        holders.addAll(occupancy.seats());
        for (UUID playerId : holders) {
            long held = Math.max(stacks.stackOf(playerId), runtime.wageredBy(playerId));
            OutcomeSettler.commitStake(commitmentKey(), playerId, held);
        }
    }

    /** Lets go of the bankroll and of everybody's credits at once. */
    private void releaseCommitments() {
        OutcomeSettler.releaseExposure(commitmentKey());
        OutcomeSettler.releaseStakes(commitmentKey());
    }

    /** Whatever a player had here goes with their seat: wagers and stack alike. */
    private void loseSeat(UUID playerId) {
        runtime.seatLost(playerId);
        stacks.remove(playerId);
        publishCommitments();
        markDirty();
    }

    // --- Ticking ------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  TableBlockEntity table) {
        table.ensureVariant();
        table.evictTheAbsent();
        table.runtime.tick();

        if (table.stateDirty) {
            table.stateDirty = false;
            table.broadcastState();
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

    @Override
    protected void loadAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.loadAdditional(tag, registries);
        this.gameId = tag.getString(KEY_GAME);
        readAccess(tag);
        this.settings = readSettings(tag);
        this.configured = tag.getBoolean(KEY_CONFIGURED);
        this.runtime = TableRuntime.forGame(game().orElse(null), this);
        this.occupancy = new TableOccupancy(game()
                .map(assigned -> Math.max(1, assigned.maxPlayers()))
                .orElse(UNASSIGNED_SEATS));
    }

    @Override
    protected void saveAdditional(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(KEY_GAME, gameId);
        writeAccess(tag);
        writeSettings(tag);
        tag.putBoolean(KEY_CONFIGURED, configured);
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

    /**
     * What the client is told on chunk load: only which game the table hosts.
     * Round state travels per player, over the mod's own channel.
     */
    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_GAME, gameId);
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
        releaseCommitments();
    }
}
