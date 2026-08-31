package com.github.arrivedbog593.tablegames.platform.block;

import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
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
 * The cashier's presence in the world.
 * <p>
 * Holds no state at all. Deposited items live in a per-player container
 * created with the menu and returned when it closes, so two people using
 * neighboring cashiers cannot see or take each other's stack, and a server
 * restart cannot strand items inside a block.
 */
public class CashierBlockEntity extends BlockEntity implements MenuProvider {

    public CashierBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CASHIER.get(), pos, state);
    }

    @Override
    public @NotNull Component getDisplayName() {
        return Component.translatable("block.tablegames.cashier");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, @NotNull Inventory inventory, @NotNull Player player) {
        return new CashierMenu(containerId, inventory, getBlockPos());
    }
}
