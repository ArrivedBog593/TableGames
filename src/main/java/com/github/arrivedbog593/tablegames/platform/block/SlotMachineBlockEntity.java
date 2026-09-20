package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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

    public SlotMachineBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SLOT_MACHINE.get(), pos, state);
        // A table builds its round when a game is chosen for it, and a
        // freshly placed block is never loaded from a tag. This one has its
        // game from the moment it exists, so it builds the reels here.
        rebuildForGame();
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

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SlotMachineBlockEntity machine) {
        machine.tick();
    }
}
