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

/**
 * A slot machine: a cabinet you stand at alone.
 * <p>
 * Its own block rather than a game a table can be set to, because a table is
 * a table. A block shaped like one that opens three spinning reels reads as
 * a bug however the code is arranged, and the shape a player sees is the
 * only documentation most of them will ever get.
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
     * A cabinet, not a cube: narrower than the block it stands in, and the
     * full height of it.
     */
    private static final VoxelShape SHAPE = Block.box(2.0, 0.0, 2.0, 14.0, 16.0, 14.0);

    public SlotMachineBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.@NotNull Builder<Block, BlockState> builder) {
        builder.add(FACING);
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
        return new SlotMachineBlockEntity(pos, state);
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
        if (level.isClientSide) {
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
        if (!(level.getBlockEntity(pos) instanceof SlotMachineBlockEntity machine)
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
        if (!(level.getBlockEntity(pos) instanceof SlotMachineBlockEntity machine)
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

        // One seat, so standing at the machine and playing it are the same
        // act — unlike a table, where watching is a thing people do. The
        // seat is still taken separately, because taking it reserves credits.
        machine.arrive(serverPlayer.getUUID());
        // State first, so the screen has something to draw on its first frame.
        CustomPacketPayload snapshot = machine.stateFor(level.getServer(), serverPlayer.getUUID());
        if (snapshot != null) {
            PacketDistributor.sendToPlayer(serverPlayer, snapshot);
        }
        PacketDistributor.sendToPlayer(serverPlayer,
                new OpenTableScreenPayload(machine.game().orElseThrow().id(), pos));
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
