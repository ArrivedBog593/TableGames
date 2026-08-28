package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteAction;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteBet;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteSession;
import com.github.arrivedbog593.tablegames.engine.session.Outcome;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * The state of one table: which game it hosts and the round in progress.
 * <p>
 * Bets are collected here and the engine session is built at spin time, with
 * a seat for everyone who wagered. That ordering matters: a session takes a
 * fixed list of seats, but a roulette table has people wandering up and
 * leaving all through the betting window. Building it once the window closes
 * sidesteps the problem entirely, and the tested engine still does every bit
 * of the payout arithmetic.
 * <p>
 * Rounds are never saved. A round is a live thing with people standing at it;
 * resuming one across a restart, with everyone logged off and their stakes
 * half committed, is worse than starting again. Only the assigned game
 * persists.
 * <p>
 * Security note: what a client is told about a round is built per player.
 * Nothing here goes over the wire wholesale.
 */
public class TableBlockEntity extends BlockEntity {

    private static final String KEY_GAME = "game";

    /** How long players have to bet once the first chip lands. */
    private static final int BETTING_TICKS = 20 * 30;

    /** How long the winning number stays on screen before the table resets. */
    private static final int RESULT_TICKS = 20 * 6;

    private String gameId = "";

    /** Players with the table open, so state can be pushed to them. */
    private final Set<UUID> viewers = new LinkedHashSet<>();

    /** Wagers taken this round, in the order they were placed. */
    private final Map<UUID, List<RouletteBet>> bets = new LinkedHashMap<>();

    private int bettingTicks;
    private int resultTicks;
    private Pocket lastResult;

    public TableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.TABLE.get(), pos, state);
    }

    // --- Assigned game -------------------------------------------------------

    /** The game this table hosts, if one is assigned and still registered. */
    public Optional<Game> game() {
        return gameId.isEmpty() ? Optional.empty() : Games.registry().get(gameId);
    }

    public String gameId() {
        return gameId;
    }

    /**
     * Assigns a game, dropping any round in progress first.
     * <p>
     * Changing the game under live wagers would leave players staked into
     * rules that no longer apply.
     */
    public void setGame(Game game) {
        abandon();
        this.gameId = game == null ? "" : game.id();
        setChanged();
        updateVariant(game == null ? TableVariant.BLANK : Games.variantOf(game));
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

    // --- Viewers ---------------------------------------------------------------

    public void addViewer(UUID playerId) {
        viewers.add(playerId);
    }

    public void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    // --- Betting ----------------------------------------------------------------

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

    public boolean isBettingOpen() {
        return resultTicks == 0;
    }

    public int secondsRemaining() {
        return bettingTicks <= 0 ? 0 : (bettingTicks + 19) / 20;
    }

    public Optional<Pocket> lastResult() {
        return resultTicks > 0 ? Optional.ofNullable(lastResult) : Optional.empty();
    }

    /**
     * The largest wager this table will take right now.
     * <p>
     * Quoted against the straight-up payout, the worst case the table offers,
     * so the figure shown to players is the one that actually binds.
     */
    public long currentTableMaximum(MinecraftServer server) {
        Optional<Game> assigned = game();
        if (assigned.isEmpty()) {
            return 0;
        }
        return OutcomeSettler.tableMaximum(server, assigned.get(),
                BetType.STRAIGHT_UP.payoutRatio());
    }

    /**
     * Takes a wager.
     * <p>
     * Checked against the table maximum, which is derived from the house
     * bankroll, and against the player's real balance minus what they have
     * already staked this round. Credits are not moved yet: they leave at
     * settlement, so a round abandoned by a restart costs nobody anything.
     *
     * @return a translation key explaining a refusal, or null when accepted
     */
    public String placeBet(ServerPlayer player, RouletteBet bet) {
        Optional<Game> assigned = game();
        if (assigned.isEmpty() || !(assigned.get() instanceof RouletteGame roulette)) {
            return "tablegames.table.unassigned";
        }
        if (!isBettingOpen()) {
            return "tablegames.roulette.betting_closed";
        }
        MinecraftServer server = player.server;

        if (!OutcomeSettler.canOpen(server, roulette)) {
            return "tablegames.roulette.house_closed";
        }
        if (bet.amount() < roulette.minimumBet()) {
            return "tablegames.reject.below_minimum_bet";
        }
        long tableMaximum = OutcomeSettler.tableMaximum(
                server, roulette, bet.type().payoutRatio());
        if (bet.amount() > tableMaximum) {
            return "tablegames.reject.above_maximum_bet";
        }
        if (bet.type().requiresTarget()
                && !roulette.wheel().pockets().contains(bet.target())) {
            return "tablegames.reject.no_such_pocket";
        }

        long balance = CreditStorage.get(server).balanceOf(player.getUUID());
        if (wageredBy(player.getUUID()) + bet.amount() > balance) {
            return "tablegames.reject.insufficient_credits";
        }

        bets.computeIfAbsent(player.getUUID(), key -> new ArrayList<>()).add(bet);
        if (bettingTicks <= 0) {
            bettingTicks = BETTING_TICKS;
        }
        broadcastState();
        return null;
    }

    /** Takes every chip this player has on the layout back off it. */
    public void clearBets(UUID playerId) {
        if (bets.remove(playerId) != null) {
            broadcastState();
        }
    }

    /** Closes the window early. Any player may call it once bets are down. */
    public void callSpin() {
        if (!bets.isEmpty() && isBettingOpen()) {
            bettingTicks = 1;
        }
    }

    // --- Ticking ------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  TableBlockEntity table) {
        if (table.resultTicks > 0) {
            table.resultTicks--;
            if (table.resultTicks == 0) {
                table.lastResult = null;
                table.broadcastState();
            }
            return;
        }
        if (table.bettingTicks > 0) {
            table.bettingTicks--;
            if (table.bettingTicks == 0) {
                table.spin();
            } else if (table.bettingTicks % 20 == 0) {
                table.broadcastState();
            }
        }
    }

    /**
     * Closes betting, runs the wheel and settles.
     * <p>
     * The engine session is built here rather than held open, with a seat for
     * each player who wagered and their real balance as its stack. Replaying
     * the bets into it gives the tested rules exactly the state they expect.
     */
    private void spin() {
        if (level == null || level.getServer() == null || bets.isEmpty()) {
            return;
        }
        MinecraftServer server = level.getServer();
        Optional<Game> assigned = game();
        if (assigned.isEmpty() || !(assigned.get() instanceof RouletteGame roulette)) {
            bets.clear();
            return;
        }

        CreditStorage storage = CreditStorage.get(server);
        List<UUID> players = new ArrayList<>(bets.keySet());
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
                session.submit(playerId, new RouletteAction.Place(bet));
            }
        }
        session.spin();

        Pocket result = session.result().orElse(null);
        Outcome outcome = session.outcome().orElse(null);
        bets.clear();
        bettingTicks = 0;

        if (outcome == null) {
            return;
        }

        OutcomeSettler.Result settled = OutcomeSettler.settle(
                server, roulette, outcome, "at " + worldPosition.toShortString());

        if (!settled.applied()) {
            // Nothing moved, so nobody lost anything. Say so rather than let
            // the round end in silence.
            tellViewers(Component.translatable(settled.reasonKey()));
            tellViewers(Component.translatable("tablegames.settle.refunded"));
            broadcastState();
            return;
        }

        lastResult = result;
        resultTicks = RESULT_TICKS;
        broadcastState();
    }

    /**
     * Ends any round in progress without settling.
     * <p>
     * Nothing to refund: wagers only become real credits at settlement, so
     * dropping them is the refund.
     */
    public void abandon() {
        bets.clear();
        bettingTicks = 0;
        resultTicks = 0;
        lastResult = null;
    }

    // --- Talking to clients ---------------------------------------------------------

    /** Pushes the round's state to everyone with the table open. */
    public void broadcastState() {
        if (level == null || level.getServer() == null) {
            return;
        }
        MinecraftServer server = level.getServer();
        for (UUID viewer : List.copyOf(viewers)) {
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null) {
                viewers.remove(viewer);
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
        for (UUID viewer : viewers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(viewer);
            if (player != null) {
                player.sendSystemMessage(message);
            }
        }
    }

    // --- Persistence -----------------------------------------------------------

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.gameId = tag.getString(KEY_GAME);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(KEY_GAME, gameId);
    }

    /**
     * What the client is told on chunk load: only which game the table hosts.
     * Round state travels per player, over the mod's own channel.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_GAME, gameId);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
