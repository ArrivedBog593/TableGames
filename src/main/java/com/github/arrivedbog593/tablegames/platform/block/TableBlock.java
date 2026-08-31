package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.network.OpenTableScreenPayload;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * A gaming table. One block serves every game.
 * <p>
 * Which game a table hosts lives in its {@link TableBlockEntity}, not in the
 * block type, so the mod registers one block no matter how many games exist.
 * The {@link #VARIANT} property only mirrors that choice for the model.
 */
public class TableBlock extends BaseEntityBlock {

    public static final MapCodec<TableBlock> CODEC = simpleCodec(TableBlock::new);

    /** Which model to draw. Mirrors the assigned game's look. */
    public static final EnumProperty<TableVariant> VARIANT =
            EnumProperty.create("variant", TableVariant.class);

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    /** Table height: a block and a half feels right to stand at. */
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 14, 16);

    public TableBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any()
                .setValue(VARIANT, TableVariant.BLANK)
                .setValue(FACING, Direction.NORTH));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT, FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected @NotNull VoxelShape getShape(@NotNull BlockState state, @NotNull BlockGetter level,
                                           @NotNull BlockPos pos, @NotNull CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new TableBlockEntity(pos, state);
    }

    /**
     * Tables tick on the server only. A betting window has to run down whether
     * or not anyone is looking, and the client has no business deciding when
     * the wheelspins.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.TABLE.get(),
                TableBlockEntity::serverTick);
    }

    /**
     * Opens the table's screen.
     * <p>
     * No menu is involved. Table games are not containers, so the server tells
     * the client which screen to open, and the client opens it. See
     * {@code OpenTableScreenPayload} for why.
     */
    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                        @NotNull Player player, @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof TableBlockEntity table)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        Optional<Game> assigned = table.game();
        if (assigned.isEmpty()) {
            player.displayClientMessage(
                    Component.translatable("tablegames.table.unassigned"), false);
            return InteractionResult.CONSUME;
        }

        // Opening a table makes you a spectator, never a player. Sitting
        // down is its own button: eight seats can be full, and a ninth person
        // still walks up to watch, which is the whole point of the split.
        table.arrive(serverPlayer.getUUID());
        // State first, so the screen has something to draw on its first frame.
        PacketDistributor.sendToPlayer(serverPlayer,
                RouletteStatePayload.forPlayer(level.getServer(), table, serverPlayer.getUUID()));
        PacketDistributor.sendToPlayer(serverPlayer,
                new OpenTableScreenPayload(assigned.get().id(), pos));
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof TableBlockEntity table) {
            // Wagers only become real credits at settlement, so dropping the
            // round is the refund.
            table.abandon();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
