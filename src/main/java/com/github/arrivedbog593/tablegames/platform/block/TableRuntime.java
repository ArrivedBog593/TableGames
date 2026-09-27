package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.engine.table.RoundPhase;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The live part of one game at one block: its round, its wagers, what it
 * tells the people watching.
 * <p>
 * Split from {@link GameBlockEntity} so that the block keeps only what every
 * game shares — which game it hosts, how it is set up, who owns it, who is
 * sitting, what each player bought in with, and what all of that commits —
 * and each game brings the rest. Roulette's betting window and felt have
 * nothing to say to a slot machine, and a slot machine's reels have nothing
 * to say to roulette.
 * <p>
 * Never persisted. A runtime is rebuilt empty whenever the block's game is
 * set or loaded, for the same reason rounds are never saved.
 */
public interface TableRuntime {

    /** Builds the runtime for whatever the table now hosts. */
    static TableRuntime forGame(Game game, GameBlockEntity table) {
        if (game instanceof RouletteGame roulette) {
            return new RouletteTable(table, roulette);
        }
        if (game instanceof SlotsGame slots) {
            return new SlotCabinet(table, slots);
        }
        return IdleRuntime.INSTANCE;
    }

    /**
     * Where the round stands. The table reads it to decide who may sit,
     * stand, vote or top up, so a game without a round of its own answers
     * {@link RoundPhase#IDLE} whenever nothing is at stake.
     */
    RoundPhase phase();

    /** Seconds until the round resolves, for anybody asking; zero when idle. */
    default int secondsRemaining() {
        return 0;
    }

    /** One server tick. */
    void tick();

    /** What this player has riding on the round in progress. */
    long wageredBy(UUID playerId);

    /**
     * Whether anything is at stake right now. A change of settings waits for
     * this to clear, so a rule is never tightened under a live wager.
     */
    boolean hasLiveStakes();

    /** What the house stands to lose on the round as it stands; zero for nothing. */
    long worstCaseHouseCost();

    /**
     * A player lost their seat, by standing up or by being away too long.
     * Anything of theirs still at stake is dropped, which is its own refund.
     */
    void seatLost(UUID playerId);

    /** The seated players' ready votes changed. A game with a clock may cut it short. */
    default void votesChanged() {
    }

    /** Drops the round in progress without settling it. */
    void abandon();

    /** What one viewer is allowed to see, or null when there is nothing to show. */
    CustomPacketPayload stateFor(MinecraftServer server, UUID viewer);

    /**
     * The results a test table running this game can be told to land on
     * next, best first; empty for a game that offers none.
     * <p>
     * Offering them is all a game does. Whether a block may be told to use
     * one is decided elsewhere — only a test table, only in a development
     * build — and a game applies one only on a test table even when told to.
     */
    default List<RigOption> rigOptions() {
        return List.of();
    }

    /**
     * Makes the next round land on this option, or lets it fall freely again
     * when given null. Good for one round only: the one after is random.
     *
     * @return false when this game offers no such option
     */
    default boolean rig(String optionId) {
        return optionId == null;
    }

    /** The option the next round has been told to land on, if any. */
    default Optional<String> rigged() {
        return Optional.empty();
    }

    /**
     * What every client near the block is told, whether or not it has the
     * game open: enough for the block to be heard, and nothing a spectator
     * could not already see. Written into the block's update, so it reaches
     * whoever is in range when the block announces it.
     */
    default void writeNews(CompoundTag tag) {
    }

    /** A table hosting nothing: no round, nothing at stake, nothing to show. */
    final class IdleRuntime implements TableRuntime {

        static final IdleRuntime INSTANCE = new IdleRuntime();

        private IdleRuntime() {
        }

        @Override
        public RoundPhase phase() {
            return RoundPhase.IDLE;
        }

        @Override
        public void tick() {
        }

        @Override
        public long wageredBy(UUID playerId) {
            return 0;
        }

        @Override
        public boolean hasLiveStakes() {
            return false;
        }

        @Override
        public long worstCaseHouseCost() {
            return 0;
        }

        @Override
        public void seatLost(UUID playerId) {
        }

        @Override
        public void abandon() {
        }

        @Override
        public CustomPacketPayload stateFor(MinecraftServer server, UUID viewer) {
            return null;
        }
    }
}
