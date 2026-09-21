package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.item.TableKeyItem;
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
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A slot machine: a cabinet one person plays and anybody may watch.
 * <p>
 * Its own block rather than a game a table can be set to, because a table is
 * a table. A block shaped like one that opens three spinning reels reads as
 * a bug however the code is arranged, and the shape a player sees is the
 * only documentation most of them will ever get.
 * <p>
 * One seat, but no limit on who stands behind it. Opening a cabinet somebody
 * else is playing shows their reels turning and their meters moving, and
 * offers none of the buttons — which is what standing at a machine in a
 * casino gets you.
 * <p>
 * What it hosts is fixed, so there is no game to pick: a bare click on a
 * machine nobody has set up goes straight to its settings, and a click on a
 * machine that is set up goes straight to the reels. The keys are only ever
 * a way back to the settings of a machine already in service.
 */
public class SlotMachineBlock extends BaseEntityBlock implements GameBlock {

    public static final MapCodec<SlotMachineBlock> CODEC = simpleCodec(SlotMachineBlock::new);

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    /**
     * Which of the two blocks a cabinet stands in this one is.
     * <p>
     * A slot machine is not furniture you look down on; it is something you
     * stand in front of. One block tall put the reels at a player's knees,
     * which is why the upper half exists: the cabinet and its tray sit in
     * the lower block, and the reel glass, the button deck and the marquee
     * sit at eye level in the one above.
     * <p>
     * Only the lower half is real. It holds the block entity, the ticker and
     * the drop; the upper half is scenery that is kept honest by
     * {@link #canSurvive} and forwards every click downwards.
     */
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;

    /**
     * Whether the reels on the outside of the cabinet are turning.
     * <p>
     * The result of a spin is settled the instant the lever is pulled, so
     * this says nothing about the outcome and cannot be read for one. It is
     * the same thing the screen shows the player, shown to everybody else in
     * the room: a machine somebody is playing looks different from one
     * nobody is, which is most of what a casino floor is made of.
     */
    public static final BooleanProperty SPINNING = BooleanProperty.create("spinning");

    /**
     * A cabinet, not a cube: inset from the block it stands in, and the full
     * height of both halves.
     */
    private static final VoxelShape SHAPE = Block.box(1.0, 0.0, 1.0, 15.0, 16.0, 15.0);

    public SlotMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any()
                .setValue(FACING, Direction.NORTH)
                .setValue(HALF, DoubleBlockHalf.LOWER)
                .setValue(SPINNING, false));
    }

    /** The half that owns the machine: itself, or the one underneath it. */
    public static BlockPos cabinetOf(BlockState state, BlockPos pos) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder) {
        builder.add(FACING, HALF, SPINNING);
    }

    /**
     * Refuses the placement outright when the cabinet has no headroom.
     * <p>
     * Returning null rather than dropping a half-height machine: a cabinet
     * with its reels missing is a machine nobody can read, and the player
     * keeps the item instead of losing it to a ceiling they forgot about.
     */
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1
                || !level.getBlockState(pos.above()).canBeReplaced(context)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(HALF, DoubleBlockHalf.LOWER);
    }

    /**
     * The upper half only ever stands on its own lower half.
     * <p>
     * That is the whole rule, and it is what makes breaking either half take
     * the other with it: {@link #updateShape} turns an orphan into air, and
     * air over the cabinet does the same downwards.
     */
    @Override
    protected boolean canSurvive(BlockState state, @NotNull LevelReader level, @NotNull BlockPos pos) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockState below = level.getBlockState(pos.below());
            return below.is(this) && below.getValue(HALF) == DoubleBlockHalf.LOWER;
        }
        return super.canSurvive(state, level, pos);
    }

    @Override
    protected @NotNull BlockState updateShape(BlockState state, @NotNull Direction direction,
                                              @NotNull BlockState neighbor, @NotNull LevelAccessor level,
                                              @NotNull BlockPos pos, @NotNull BlockPos neighborPos) {
        DoubleBlockHalf half = state.getValue(HALF);
        boolean towardsTheOtherHalf = direction.getAxis() == Direction.Axis.Y
                && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP);
        if (towardsTheOtherHalf) {
            return neighbor.is(this) && neighbor.getValue(HALF) != half
                    ? state
                    : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    /**
     * Stops a creative player who broke the reels from being handed a second
     * machine by the half still standing.
     * <p>
     * Survival needs none of this: the drop is the lower half's, and the
     * loot table asks which half it is before it gives anything.
     */
    @Override
    public @NotNull BlockState playerWillDestroy(@NotNull Level level, @NotNull BlockPos pos,
                                                 @NotNull BlockState state, @NotNull Player player) {
        if (!level.isClientSide && player.isCreative()
                && state.getValue(HALF) == DoubleBlockHalf.UPPER) {
            BlockPos cabinet = pos.below();
            BlockState below = level.getBlockState(cabinet);
            if (below.is(state.getBlock()) && below.getValue(HALF) == DoubleBlockHalf.LOWER) {
                level.setBlock(cabinet, Blocks.AIR.defaultBlockState(),
                        Block.UPDATE_SUPPRESS_DROPS | Block.UPDATE_ALL);
                level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, cabinet, Block.getId(below));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
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

    /** The machine lives in the lower half; the upper one is a facade. */
    @Nullable
    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? new SlotMachineBlockEntity(pos, state)
                : null;
    }

    /**
     * Remembers who put the machine down.
     * <p>
     * It buys them less than it buys the owner of a table: slots pay out of
     * the house's bankroll, so the limits and the payback are authority's to
     * set wherever the cabinet stands. What ownership still gives is the
     * right to be rid of it.
     */
    @Override
    public void setPlacedBy(@NotNull Level level, @NotNull BlockPos pos, @NotNull BlockState state,
                            @Nullable LivingEntity placer, @NotNull ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // The reels and the marquee: put down by the same act that put down
        // the cabinet, so a player never has to stack one on the other.
        level.setBlock(pos.above(), state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
        if (level.isClientSide || !(placer instanceof Player player)) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof SlotMachineBlockEntity machine) {
            machine.claim(player.getUUID());
        }
    }

    /** Server side only: the reels run down whether or not anybody is looking. */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, @NotNull BlockState state, @NotNull BlockEntityType<T> type) {
        if (level.isClientSide || state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return null;
        }
        return createTickerHelper(type, ModBlockEntities.SLOT_MACHINE.get(),
                SlotMachineBlockEntity::serverTick);
    }

    /**
     * Either key reopens the machine's settings instead of playing it.
     * <p>
     * The key grants nothing. Whether the settings open is the machine's
     * decision, the same one a bare click asks, and for a house game the
     * answer is no to everybody but an operator or a casino admin.
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
        if (!(level.getBlockEntity(cabinetOf(state, pos)) instanceof SlotMachineBlockEntity machine)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        Component refusal = machine.configureRefusal(serverPlayer);
        if (refusal != null) {
            serverPlayer.displayClientMessage(refusal, true);
            return ItemInteractionResult.CONSUME;
        }
        PacketDistributor.sendToPlayer(serverPlayer, OpenTableConfigPayload.of(machine));
        return ItemInteractionResult.CONSUME;
    }

    /**
     * Opens the reels, or the settings when the machine has never been set
     * up.
     * <p>
     * A machine placed and left alone posts no limits and no payback, so it
     * stays closed until somebody who may set it up has saved one. Whoever
     * that is gets walked there on a bare click, with no key needed;
     * everybody else is told to come back later.
     */
    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level,
                                                        @NotNull BlockPos pos, @NotNull Player player,
                                                        @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        // A click on the reels is a click on the machine: the upper half has
        // nothing of its own to open.
        BlockPos cabinet = cabinetOf(state, pos);
        if (!(level.getBlockEntity(cabinet) instanceof SlotMachineBlockEntity machine)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        if (!machine.isConfigured()) {
            if (machine.mayConfigure(serverPlayer)) {
                PacketDistributor.sendToPlayer(serverPlayer, OpenTableConfigPayload.of(machine));
            } else {
                player.displayClientMessage(machine.configureRefusal(serverPlayer), true);
            }
            return InteractionResult.CONSUME;
        }

        // Anybody may stand at a cabinet; only one may play it. Opening it
        // makes you a watcher, and a watcher sees what the player sees — the
        // reels turning, what they landed on, what the meters say — because
        // that is what standing behind somebody at a machine is. Taking the
        // seat is the separate act, and the screen says who has it.
        machine.arrive(serverPlayer.getUUID());
        // State first, so the screen has something to draw on its first frame.
        CustomPacketPayload snapshot = machine.stateFor(level.getServer(), serverPlayer.getUUID());
        if (snapshot != null) {
            PacketDistributor.sendToPlayer(serverPlayer, snapshot);
        }
        PacketDistributor.sendToPlayer(serverPlayer,
                new OpenTableScreenPayload(machine.game().orElseThrow().id(), cabinet));
        return InteractionResult.CONSUME;
    }

    @Override
    protected void onRemove(BlockState state, @NotNull Level level, @NotNull BlockPos pos,
                            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof SlotMachineBlockEntity machine) {
            machine.abandon();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
