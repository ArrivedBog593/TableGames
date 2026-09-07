package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.menu.AdminCashierMenu;
import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * The casino cashier: items in, credits out, and back again.
 * <p>
 * A separate block from the gaming table because it is not a game. It has no
 * players, no turns, and no outcome, so routing it through the game registry
 * would mean {@code Game} describing something that is not one.
 */
public class CashierBlock extends BaseEntityBlock {

    public static final MapCodec<CashierBlock> CODEC = simpleCodec(CashierBlock::new);

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public CashierBlock(Properties properties) {
        super(properties);
        registerDefaultState(getStateDefinition().any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected @NotNull MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected @NotNull RenderShape getRenderShape(@NotNull BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public BlockEntity newBlockEntity(@NotNull BlockPos pos, @NotNull BlockState state) {
        return new CashierBlockEntity(pos, state);
    }

    /**
     * The administration card opens the settings screen instead of the till.
     * <p>
     * Mirrors the shop block exactly, including consuming the click when the
     * card is not theirs: falling through would open the customer's cashier,
     * which reads as the card having worked.
     */
    @Override
    protected @NotNull ItemInteractionResult useItemOn(ItemStack stack, @NotNull BlockState state,
                                                       @NotNull Level level, @NotNull BlockPos pos,
                                                       @NotNull Player player,
                                                       @NotNull InteractionHand hand,
                                                       @NotNull BlockHitResult hit) {
        if (!(stack.getItem() instanceof AdminKeyItem)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!AdminKeyItem.mayAdminister(serverPlayer, stack)) {
            serverPlayer.displayClientMessage(
                    Component.translatable("tablegames.admin.key_not_yours"), true);
            return ItemInteractionResult.CONSUME;
        }

        PacketDistributor.sendToPlayer(serverPlayer, CashierCatalogPayload.current());
        serverPlayer.openMenu(new SimpleMenuProvider(
                        (containerId, inventory, who) ->
                                new AdminCashierMenu(containerId, inventory, pos),
                        Component.translatable("tablegames.admin.cashier.title")),
                buffer -> buffer.writeBlockPos(pos));
        return ItemInteractionResult.CONSUME;
    }

    @Override
    protected @NotNull InteractionResult useWithoutItem(@NotNull BlockState state, Level level, @NotNull BlockPos pos,
                                                        @NotNull Player player, @NotNull BlockHitResult hit) {
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level.getBlockEntity(pos) instanceof MenuProvider provider)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        // The catalog has to reach the client before the screen opens, or
        // the first frame renders an empty list.
        PacketDistributor.sendToPlayer(serverPlayer, CashierCatalogPayload.current());
        serverPlayer.openMenu(provider, buffer -> buffer.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }
}
