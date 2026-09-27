package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.Optional;

/**
 * One slot machine: a cabinet that hosts one game and never another.
 * <p>
 * Everything a machine shares with a table — who may set it up, how it is
 * set up, who is standing at it, what they fed into it — is
 * {@link GameBlockEntity}'s. What is left here is the short answer to which
 * game this is, and it is a constant.
 * <p>
 * That constant is why there is no identity to persist. A table has to
 * remember which game was chosen for it; a machine is a machine on every
 * load, so the saved tag holds only how it was set up and who may set it up.
 * It still has to be set up before anybody plays: a machine placed and left
 * alone posts no limits, and a payback nobody chose is not one the house
 * should be held to.
 */
public class SlotMachineBlockEntity extends GameBlockEntity {

    private static final String KEY_FANFARE = "fanfare";

    /**
     * Who is told when a cabinet's reels start or stop, on the client.
     * <p>
     * Set once by the client's setup and never on a dedicated server, which
     * has no sound system to hand it to. An interface in the middle rather
     * than a call into the client package, so this class, which both sides
     * load, never names a class only one side has.
     */
    private static Listener listener = Listener.DEAF;

    /**
     * What the last update from the server said the room should hear.
     * <p>
     * Client side only, and only ever the coarse kind of result — see
     * {@link SlotFanfare}. It arrives with the word that the reels have
     * stopped and is not played until they have finished coming down.
     */
    private SlotFanfare heard = SlotFanfare.NONE;

    public SlotMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SLOT_MACHINE.get(), pos, state);
        // A table builds its round when a game is chosen for it, and a
        // freshly placed block is never loaded from a tag. This one has its
        // game from the moment it exists, so it builds the reels here.
        rebuildForGame();
    }

    /** Hands the client's cabinet sounds the news of every machine it can see. */
    public static void listen(Listener listener) {
        SlotMachineBlockEntity.listener = Objects.requireNonNull(listener, "listener");
    }

    @Override
    public Optional<Game> game() {
        return Optional.ofNullable(Games.slots());
    }

    /**
     * Short, because the seat is the machine.
     * <p>
     * Long enough to check an inventory or answer somebody, short enough that
     * a cabinet is not held by a player who walked off. Nothing is lost when
     * it runs out: the credits on the meter were only ever reserved against a
     * balance, so they go back to it, and the player buys in again.
     */
    @Override
    protected int absenceSeconds() {
        return 20;
    }

    /** The reels, if the registry gave this block the game it expects. */
    public Optional<SlotCabinet> reels() {
        return runtime() instanceof SlotCabinet cabinet ? Optional.of(cabinet) : Optional.empty();
    }

    /** What the room should hear once the reels are down, as last told by the server. */
    public SlotFanfare heard() {
        return heard;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SlotMachineBlockEntity machine) {
        machine.tick();
        machine.showReels(level, pos, state);
    }

    /**
     * Puts on the outside of the cabinet what the player sees on the screen:
     * reels that turn while a spin is running and stand still when it is
     * over.
     * <p>
     * Only ever a mirror of {@link SlotCabinet#isRolling()}, and only ever
     * the fact that something is turning — never what it landed on. The
     * result is already settled when the reels start, so a spectator who
     * reads the block reads nothing the screen would not have told them, and
     * nothing the player does not know first.
     * <p>
     * Both halves carry the flag because a block state cannot be read from
     * the block next door. The upper half is the one with the reel glass on
     * it; the lower one is set to keep the pair from disagreeing when the
     * chunk reloads.
     * <p>
     * The same update carries the fanfare. Changing the lower half's state
     * sends its block entity along with it, and {@link #getUpdateTag} only
     * has one to send once the spin is over — so the room learns what to
     * cheer for in the same breath as it learns the reels have stopped, and
     * not a tick sooner.
     */
    private void showReels(Level level, BlockPos pos, BlockState state) {
        boolean rolling = reels().map(SlotCabinet::isRolling).orElse(false);
        if (state.getValue(SlotMachineBlock.SPINNING) == rolling) {
            return;
        }
        // Clients only: neighbors have nothing to say about the reels, and
        // waking them every time a spin starts would be a lot of noise for
        // a texture swap.
        level.setBlock(pos, state.setValue(SlotMachineBlock.SPINNING, rolling), Block.UPDATE_CLIENTS);
        BlockPos above = pos.above();
        BlockState reelGlass = level.getBlockState(above);
        if (reelGlass.is(state.getBlock())) {
            level.setBlock(above, reelGlass.setValue(SlotMachineBlock.SPINNING, rolling),
                    Block.UPDATE_CLIENTS);
        }
    }

    // --- What the room hears -------------------------------------------------------

    /**
     * The fanfare, and nothing else.
     * <p>
     * This is what every client that can see the machine is sent, so it
     * carries none of what the saved tag does: not the owner, not the guests,
     * not the limits. {@link SlotCabinet#fanfare()} is silent while the reels
     * turn, which is what keeps the result off every client until it is over.
     */
    @Override
    public @NotNull CompoundTag getUpdateTag(HolderLookup.@NotNull Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(KEY_FANFARE, reels().map(SlotCabinet::fanfare).orElse(SlotFanfare.NONE).name());
        return tag;
    }

    /**
     * Reads the fanfare and stops there.
     * <p>
     * The default would load the tag as if it were a save, and on a tag that
     * holds one key that means clearing everything else this client knows
     * about the machine.
     */
    @Override
    public void onDataPacket(@NotNull Connection connection, @NotNull ClientboundBlockEntityDataPacket packet,
                             HolderLookup.@NotNull Provider registries) {
        hear(packet.getTag());
    }

    /** The same, for the tag a client is sent when the chunk first reaches it. */
    @Override
    public void handleUpdateTag(@NotNull CompoundTag tag, HolderLookup.@NotNull Provider registries) {
        hear(tag);
    }

    private void hear(CompoundTag tag) {
        try {
            heard = SlotFanfare.valueOf(tag.getString(KEY_FANFARE));
        } catch (IllegalArgumentException unknown) {
            // A server newer or older than this client. Silence is the safe
            // thing to play for a result it cannot name.
            heard = SlotFanfare.NONE;
        }
    }

    /**
     * Tells the client's sounds when the reels start and stop.
     * <p>
     * Here rather than in a ticker because this is the exact moment the new
     * state is applied — a ticker would notice up to a tick later, and the
     * landings it times would come down a tick away from the ones on the
     * glass.
     */
    @Override
    public void setBlockState(@NotNull BlockState state) {
        boolean was = getBlockState().getValue(SlotMachineBlock.SPINNING);
        super.setBlockState(state);
        boolean turning = state.getValue(SlotMachineBlock.SPINNING);
        if (level != null && level.isClientSide && was != turning) {
            listener.reelsTurned(this, turning);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && level.isClientSide) {
            listener.cabinetGone(this);
        }
    }

    /** What the client does with a cabinet's news. */
    public interface Listener {

        /** Nothing: a server, or a client that has not set up yet. */
        Listener DEAF = new Listener() {
            @Override
            public void reelsTurned(SlotMachineBlockEntity machine, boolean turning) {
            }

            @Override
            public void cabinetGone(SlotMachineBlockEntity machine) {
            }
        };

        /** The reels have started turning, or the server has said they are to stop. */
        void reelsTurned(SlotMachineBlockEntity machine, boolean turning);

        /** The cabinet is gone: broken, or its chunk unloaded. */
        void cabinetGone(SlotMachineBlockEntity machine);
    }
}
