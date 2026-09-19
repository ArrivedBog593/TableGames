package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.item.TableKeyItem;
import com.github.arrivedbog593.tablegames.platform.network.OpenGamePickerPayload;
import com.github.arrivedbog593.tablegames.platform.network.OpenTableConfigPayload;
import com.github.arrivedbog593.tablegames.platform.network.OpenTableScreenPayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
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
     * Remembers who put the table down.
     * <p>
     * That is what lets somebody set up a table in their own base without an
     * operator having to come over and choose the game for them. A table
     * placed by anything that is not a player keeps no owner and stays
     * operator business.
     */
    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide || !(placer instanceof Player player)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof TableBlockEntity table) {
            table.claim(player.getUUID());
        }
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
     * Either key opens the table's setup instead of the game.
     * <p>
     * Only a configured table needs one: an empty or half set up table opens
     * its setup on a bare click already. The keys grant nothing: whether the
     * setup opens is the table's decision, the same one its commands ask. The
     * click is consumed when the answer is no, because falling through would
     * open the game, which reads as the key having worked.
     */
    @Override
    protected @NotNull ItemInteractionResult useItemOn(ItemStack stack, @NotNull BlockState state,
                                                       @NotNull Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player,
                                                       @NotNull InteractionHand hand,
                                                       @NotNull BlockHitResult hit) {
        if (!(stack.getItem() instanceof TableKeyItem) && !(stack.getItem() instanceof AdminKeyItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof TableBlockEntity table)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!table.mayConfigure(serverPlayer)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("tablegames.command.table.not_yours"), true);
            return ItemInteractionResult.CONSUME;
        }
        openSetup(serverPlayer, table);
        return ItemInteractionResult.CONSUME;
    }

    /**
     * Shows whichever setup step the table is at: the list of games when it
     * hosts none, that game's settings once one is chosen.
     * <p>
     * Callers check the player may configure the table first.
     */
    public static void openSetup(ServerPlayer player, TableBlockEntity table) {
        if (table.game().isEmpty()) {
            PacketDistributor.sendToPlayer(player,
                    new OpenGamePickerPayload(table.getBlockPos()));
        } else {
            PacketDistributor.sendToPlayer(player, OpenTableConfigPayload.of(table));
        }
    }

    /**
     * Opens the table's screen, or its setup when it is not ready to play.
     * <p>
     * No menu is involved. Table games are not containers, so the server tells
     * the client which screen to open, and the client opens it. See
     * {@code OpenTableScreenPayload} for why.
     * <p>
     * A table that is not ready walks whoever may set it up through the next
     * step with no key needed, and tells everybody else to come back later.
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
        if (assigned.isEmpty() || !table.isConfigured()) {
            if (table.mayConfigure(serverPlayer)) {
                openSetup(serverPlayer, table);
            } else {
                player.displayClientMessage(Component.translatable(assigned.isEmpty()
                        ? "tablegames.table.unassigned"
                        : "tablegames.table.being_set_up"), true);
            }
            return InteractionResult.CONSUME;
        }

        // Opening a table makes you a spectator, never a player. Sitting
        // down is its own button: eight seats can be full, and a ninth person
        // still walks up to watch, which is the whole point of the split.
        table.arrive(serverPlayer.getUUID());
        // State first, so the screen has something to draw on its first frame.
        CustomPacketPayload snapshot = table.stateFor(level.getServer(), serverPlayer.getUUID());
        if (snapshot != null) {
            PacketDistributor.sendToPlayer(serverPlayer, snapshot);
        }
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