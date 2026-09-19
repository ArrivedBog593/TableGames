package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.item.AdminKeyItem;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Configuring what the cashier pays for.
 * <p>
 * The same shape as {@link AdminShopMenu} and for the same reason: pricing an
 * item means putting one in a slot. Typing an item id into a command works
 * until somebody has to spell out a modded id from memory, and the thing they
 * want to price is already in their hand.
 * <p>
 * It carries the buyback surcharge as well, because that is the cashier's own
 * setting rather than the casino's: it is the cut this counter keeps, so it
 * belongs where the counter is configured. The exposure percentage and the
 * reserve are not here, since those are about the bankroll behind every table
 * and have nothing to do with this block.
 */
public class AdminCashierMenu extends AbstractContainerMenu {

    /** Geometry, shared with the screen. Slots are positioned here. */
    public static final int PANEL_WIDTH = 220;
    public static final int PANEL_HEIGHT = 273;
    public static final int INPUT_X = 8;
    public static final int INPUT_Y = 120;
    public static final int INVENTORY_Y = 190;
    public static final int HOTBAR_Y = 248;

    private final ContainerLevelAccess access;
    private final Player player;

    /** What is about to be priced. Never persisted; emptied back to the player. */
    private final SimpleContainer input = new SimpleContainer(1);

    /**
     * The buyback surcharge, as a percentage.
     * <p>
     * Sent rather than assumed, so the field opens showing what is actually
     * set. An admin editing a box that started at zero would lower a
     * ten-percent surcharge to nothing by typing nothing at all.
     */
    private final DataSlot spread = DataSlot.standalone();

    /** Client-side constructor: reads the block position the server wrote. */
    public AdminCashierMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public AdminCashierMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.ADMIN_CASHIER.get(), containerId);
        this.player = inventory.player;
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

        addDataSlot(spread);
        refreshSpread();
    }

    /** What is currently in the pricing slot. */
    public ItemStack held() {
        return input.getItem(0);
    }

    /** The buyback surcharge the server currently has set. */
    public int spreadPercent() {
        return spread.get();
    }

    /** Empties the pricing slot, for after something has been priced. */
    public void clearInput() {
        input.setItem(0, ItemStack.EMPTY);
        broadcastChanges();
    }

    /** Rereads the surcharge, for after somebody changes it. */
    public void refreshSpread() {
        if (player instanceof ServerPlayer server) {
            spread.set(EconomyData.get(server.server).spreadPercent());
        }
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
        // Vanilla's check covers the block still being there as well as reach.
        return stillValid(access, who, ModBlocks.cashier());
    }

    @Override
    public void removed(@NotNull Player who) {
        super.removed(who);
        // The stack was only ever borrowed to name an item.
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
