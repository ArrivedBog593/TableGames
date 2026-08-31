package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.menu.AdminShopMenu;
import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;
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
 * The casino shop: credits in, goods out, nothing sold back.
 * <p>
 * Separate from the cashier because the two are opposites. The cashier is
 * symmetric and neutral — whatever goes in comes back out at the same rate.
 * The shop only ever takes credits, which is precisely why it works as a
 * sink.
 */
public class ShopBlock extends BaseEntityBlock {

    public static final MapCodec<ShopBlock> CODEC = simpleCodec(ShopBlock::new);

    public static final EnumProperty<Direction> FACING = HorizontalDirectionalBlock.FACING;

    public ShopBlock(Properties properties) {
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
        return new ShopBlockEntity(pos, state);
    }

    /**
     * Opens the settings when the admin key is what is being held.
     * <p>
     * Here rather than on the key itself. The server tries the block first and
     * only reaches {@code Item.useOn} if nothing consumed the click, so a
     * check written on the item never ran — this block had already opened the
     * customer's shop and consumed the action.
     */
    @Override
    protected @NotNull ItemInteractionResult useItemOn(ItemStack stack, @NotNull BlockState state, @NotNull Level level,
                                                       @NotNull BlockPos pos, @NotNull Player player, @NotNull InteractionHand hand,
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
            // Consumed rather than passed on: falling through would open the
            // customer's shop, which reads as the key having worked.
            return ItemInteractionResult.CONSUME;
        }

        PacketDistributor.sendToPlayer(serverPlayer,
                ShopCatalogPayload.current(level.getServer()));
        serverPlayer.openMenu(new SimpleMenuProvider(
                        (containerId, inventory, who) ->
                                new AdminShopMenu(containerId, inventory, pos),
                        Component.translatable("tablegames.admin.shop.title")),
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
        // the first frame renders an empty shop.
        PacketDistributor.sendToPlayer(serverPlayer, ShopCatalogPayload.current(level.getServer()));
        serverPlayer.openMenu(provider, buffer -> buffer.writeBlockPos(pos));
        return InteractionResult.CONSUME;
    }
}