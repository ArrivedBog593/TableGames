package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The shop's menu: a balance, a catalog, and the player's own inventory.
 * <p>
 * No slots of its own. Nothing is deposited here — items only ever leave the
 * shop — so there is no tray to guard and nothing to hand back on close.
 * <p>
 * Buying does not happen here. It used to, through {@code clickMenuButton},
 * which carries a single {@code int} and so had room for an entry number and
 * nothing else — no quantity, and no way to say what price the screen was
 * showing. Purchases now arrive as
 * {@code ShopPurchasePayload}, and this class is left holding what a menu is
 * actually for: slots and synchronized numbers.
 * <p>
 * Data slots are written on the server only, since the client's copy of this
 * class has no access to the credit store and would zero them.
 */
public class ShopMenu extends AbstractContainerMenu {

    private final ContainerLevelAccess access;
    private final Player player;

    /** Balance is long; data slots carry ints, so it travels in halves. */
    private final DataSlot balanceLow = DataSlot.standalone();
    private final DataSlot balanceHigh = DataSlot.standalone();

    /**
     * What the player has riding on a round that has not settled.
     * <p>
     * Sent because the screen cannot work it out and cannot be told any other
     * way. Wagers are not debited when they are placed, so a balance of a
     * thousand with a thousand on the felt buys nothing — and a shop that
     * drew those items as affordable and then refused the click without a
     * word looked broken rather than correct.
     */
    private final DataSlot committedLow = DataSlot.standalone();
    private final DataSlot committedHigh = DataSlot.standalone();

    /**
     * Panel geometry, shared with the screen.
     * <p>
     * Slots are positioned here and drawn there, so the two have to agree.
     * Keeping the numbers on the side that owns the slots means the screen
     * can read them; the reverse would have the server importing client code.
     * <p>
     * Wider than it was because the cart needs a column of its own. Putting
     * it under the grid instead would have pushed the inventory far enough
     * down to matter at large GUI scales, and width is the cheaper of the two:
     * a panel three hundred and forty wide still fits comfortably on a screen
     * that a three-hundred-tall one does not.
     */
    public static final int PANEL_WIDTH = 340;
    // Taller than the old textured panel: once to clear the row of controls
    // above the catalog, again by eleven for the balance, which now has a row
    // to itself rather than sharing the title's, and again for the two extra
    // grid rows the cart column left room for. Height is free once the frame
    // is drawn instead of blitted, which is the whole reason the layout can
    // keep answering questions like this one with a row.
    public static final int PANEL_HEIGHT = 264;
    public static final int INVENTORY_Y = 181;
    public static final int HOTBAR_Y = 239;

    /** Client-side constructor: reads the block position the server wrote. */
    public ShopMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public ShopMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.SHOP.get(), containerId);
        this.player = inventory.player;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);

        // Centered under a panel that is no longer a fixed 176 wide. The
        // screen draws its frame from rectangles rather than a texture, so
        // the width is a layout decision — but the slots live here, on the
        // server, and would sit left-aligned under a wider panel if this
        // still assumed the old one.
        int inventoryLeft = (PANEL_WIDTH - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        inventoryLeft + column * 18, INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column,
                    inventoryLeft + column * 18, HOTBAR_Y));
        }

        addDataSlot(balanceLow);
        addDataSlot(balanceHigh);
        addDataSlot(committedLow);
        addDataSlot(committedHigh);
        refreshBalance();
    }

    /** The player's balance, reassembled from its two halves. */
    public long balance() {
        return ((long) balanceHigh.get() << 32) | (balanceLow.get() & 0xFFFFFFFFL);
    }

    /** What of that balance is already promised to a live round. */
    public long committed() {
        return ((long) committedHigh.get() << 32) | (committedLow.get() & 0xFFFFFFFFL);
    }

    /**
     * What the player can actually spend here.
     * <p>
     * What every price comparison on the screen has to use. Comparing against
     * the balance draws a chip a player cannot buy as though they could.
     */
    public long spendable() {
        return Math.max(0L, balance() - committed());
    }

    private boolean isServerSide() {
        return !player.level().isClientSide && player.level().getServer() != null;
    }

    private void refreshBalance() {
        if (!isServerSide()) {
            return;
        }
        long balance = storage().balanceOf(player.getUUID());
        balanceLow.set((int) (balance & 0xFFFFFFFFL));
        balanceHigh.set((int) (balance >> 32));

        long committed = OutcomeSettler.stakes().committedBy(player.getUUID());
        committedLow.set((int) (committed & 0xFFFFFFFFL));
        committedHigh.set((int) (committed >> 32));
    }

    private CreditStorage storage() {
        return CreditStorage.get(player.level().getServer());
    }

    @Override
    public void broadcastChanges() {
        refreshBalance();
        super.broadcastChanges();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player who, int index) {
        // Nothing to move: the shop has no slots of its own, and shift-clicking
        // your own items should not make them disappear into it.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(@NotNull Player who) {
        return stillValid(access, who, ModBlocks.shop());
    }
}