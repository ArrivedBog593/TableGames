package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The shop's presence in the world.
 * <p>
 * Stateless. Stock is unlimited on purpose: the shop exists to absorb credits
 * rather than to simulate a supply chain, and a shop that runs out stops
 * doing its one job at exactly the moment the economy needs it most.
 */
public class ShopBlockEntity extends BlockEntity implements MenuProvider {

    public ShopBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SHOP.get(), pos, state);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.tablegames.shop");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory inventory, @NotNull Player player) {
        return new ShopMenu(containerId, inventory, getBlockPos());
    }
}
