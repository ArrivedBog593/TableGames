package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Configuring what the shop sells.
 * <p>
 * A menu rather than a plain screen because it moves items: putting a stack
 * in the slot is how an admin says what to put on sale, components and all.
 * There is no way to express "this netherite sword, with these five
 * enchantments" in a command, so the slot is not a convenience — it is the
 * only workable interface for the thing the shop now stores.
 * <p>
 * The slot holds the admin's own item and hands it back when the screen
 * closes. Listing something copies it rather than consuming it, so an admin
 * does not lose the sword they used to describe the sale.
 */
public class AdminShopMenu extends AbstractContainerMenu {

    /** Geometry, shared with the screen. Slots are positioned here. */
    public static final int PANEL_WIDTH = 220;
    public static final int PANEL_HEIGHT = 236;
    public static final int INPUT_X = 8;
    public static final int INPUT_Y = 120;
    public static final int INVENTORY_Y = 153;
    public static final int HOTBAR_Y = 211;

    private final ContainerLevelAccess access;

    /** What is about to be listed. Never persisted; emptied back to the player. */
    private final SimpleContainer input = new SimpleContainer(1);

    /** Client-side constructor: reads the block position the server wrote. */
    public AdminShopMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public AdminShopMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.ADMIN_SHOP.get(), containerId);
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);

        addSlot(new Slot(input, 0, INPUT_X, INPUT_Y));

        int inventoryLeft = (PANEL_WIDTH - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        inventoryLeft + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, inventoryLeft + column * 18, HOTBAR_Y));
        }
    }

    /** What is currently in the listing slot. */
    public ItemStack held() {
        return input.getItem(0);
    }

    /** Empties the listing slot, for after something has been listed. */
    public void clearInput() {
        input.setItem(0, ItemStack.EMPTY);
        broadcastChanges();
    }

    /**
     * Whether this player may still be looking at this screen.
     * <p>
     * Re-checked every tick rather than only when the menu opens, so an
     * administrator who is revoked mid-edit is thrown out immediately instead
     * of finishing whatever they had started.
     */
    @Override
    public boolean stillValid(@NotNull Player who) {
        if (who instanceof ServerPlayer server
                && !AdminKeyItem.mayAdminister(server, server.getMainHandItem())) {
            return false;
        }
        return access.evaluate((level, pos) ->
                who.distanceToSqr(pos.getCenter()) <= 64.0, true);
    }

    @Override
    public void removed(@NotNull Player who) {
        super.removed(who);
        // The stack was only ever borrowed to describe a sale.
        access.execute((level, pos) -> clearContainer(who, input));
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player who, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }
}
