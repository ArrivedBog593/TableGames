package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * One table: which game it hosts, and how it looks while hosting it.
 * <p>
 * Everything a table shares with any other block people play at — who may
 * set it up, how it is set up, who is standing at it, what they have
 * reserved — lives in {@link GameBlockEntity}. What is left here is the one
 * thing that makes a table a table: the game is not fixed. A table is placed
 * blank, takes whichever game is chosen for it, and can be emptied and given
 * another.
 * <p>
 * That choice is also why the block has a look to keep in step. A slot
 * machine is always a slot machine and its model never changes; a table
 * wears the game it currently hosts, and the two can drift apart in ways
 * {@link #ensureVariant()} explains.
 */
public class TableBlockEntity extends GameBlockEntity {

    private static final String KEY_GAME = "game";

    private String gameId = "";

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
    @Override
    public Optional<Game> game() {
        return gameId.isEmpty() ? Optional.empty() : Games.registry().get(gameId);
    }

    /** What this table was last set to, registered or not. */
    @Override
    public String gameId() {
        return gameId;
    }

    /** The live game when it is a roulette wheel. */
    public Optional<RouletteTable> roulette() {
        return runtime() instanceof RouletteTable wheel ? Optional.of(wheel) : Optional.empty();
    }

    /**
     * Assigns a game, dropping any round in progress and standing everyone up.
     * <p>
     * Changing the game under live wagers would leave players staked into
     * rules that no longer apply and seated into a table whose seat count may
     * have just changed underneath them.
     */
    public void setGame(Game game) {
        this.gameId = game == null ? "" : game.id();
        resetForNewGame();
        updateVariant(game == null ? TableVariant.BLANK : Games.variantOf(game));
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

    // --- Ticking ------------------------------------------------------------------

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  TableBlockEntity table) {
        table.ensureVariant();
        table.tick();
    }

    // --- Persistence -----------------------------------------------------------

    @Override
    protected void readIdentity(@NotNull CompoundTag tag) {
        this.gameId = tag.getString(KEY_GAME);
    }

    @Override
    protected void writeIdentity(@NotNull CompoundTag tag) {
        tag.putString(KEY_GAME, gameId);
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
}
