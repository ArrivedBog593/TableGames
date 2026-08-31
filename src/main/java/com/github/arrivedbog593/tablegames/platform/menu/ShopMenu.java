package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.ShopEntry;
import com.github.arrivedbog593.tablegames.platform.economy.ShopExchange;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The shop's menu: a balance, a catalog, and the player's own inventory.
 * <p>
 * No slots of its own. Nothing is deposited here — items only ever leave the
 * shop — so there is no tray to guard and nothing to hand back on close.
 * <p>
 * Prices are read from the live table on every purchase, never from what the
 * client sent. A client is free to claim an item costs one credit; the server
 * is not obliged to believe it.
 * <p>
 * Data slots are written on the server only, since the client's copy of this
 * class has no access to the credit store and would zero them.
 */
public class ShopMenu extends AbstractContainerMenu {

     /**
     * Offsets a shop entry's number to say "buy one of this".
     * <p>
     * Far enough apart that a number can never reach the next band. A
     * catalog that large is a mistake rather than a shop, but the cost of the
     * headroom is nothing, and the failure without it would be silent: asking
     * for one and being sold a stack.
     */
    public static final int BUTTON_BUY_ONE = 1_000_000;

    /** Button ids at or above this buy a full stack of entry (id - base). */
    public static final int BUTTON_BUY_STACK = 2_000_000;

    private final ContainerLevelAccess access;
    private final Player player;

    /** Balance is long; data slots carry ints, so it travels in halves. */
    private final DataSlot balanceLow = DataSlot.standalone();
    private final DataSlot balanceHigh = DataSlot.standalone();

    /**
     * Panel geometry, shared with the screen.
     * <p>
     * Slots are positioned here and drawn there, so the two have to agree.
     * Keeping the numbers on the side that owns the slots means the screen
     * can read them; the reverse would have the server importing client code.
     */
    public static final int PANEL_WIDTH = 220;
    // Six pixels taller than the old textured panel, to clear the row of
    // controls that now sits above the catalog. Height is free once the frame
    // is drawn instead of blitted.
    public static final int PANEL_HEIGHT = 213;
    public static final int INVENTORY_Y = 130;
    public static final int HOTBAR_Y = 188;

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
        refreshBalance();
    }

    /** The player's balance, reassembled from its two halves. */
    public long balance() {
        return ((long) balanceHigh.get() << 32) | (balanceLow.get() & 0xFFFFFFFFL);
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
    }

    private CreditStorage storage() {
        return CreditStorage.get(player.level().getServer());
    }

    @Override
    public boolean clickMenuButton(Player who, int id) {
        if (who.level().isClientSide) {
            return true;
        }
        if (!(who instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        if (id >= BUTTON_BUY_STACK) {
            return buy(serverPlayer, storage(), id - BUTTON_BUY_STACK, true);
        }
        if (id >= BUTTON_BUY_ONE) {
            return buy(serverPlayer, storage(), id - BUTTON_BUY_ONE, false);
        }
        return false;
    }

    /**
     * Buys from the catalog.
     * <p>
     * The number is the entry's place in the catalog as the server sent it,
     * not the row it happened to be drawn in — the screen sorts and filters
     * locally, so those are rarely the same.
     * <p>
     * Everything about the purchase then comes from {@link EconomyData}, so a
     * stale or tampered catalog cannot change what anything costs or what it
     * delivers. A number that no longer points anywhere simply fails: the
     * catalog can change while a screen is open, and buying whatever slid
     * into that place would be worse than buying nothing.
     */
    private boolean buy(ServerPlayer who, CreditStorage storage,
                        int number, boolean wholeStack) {
        Optional<ShopEntry> entry = EconomyData.get(who.server).shopEntry(number);
        if (entry.isEmpty()) {
            return false;
        }

        long wanted = wholeStack
                ? Math.max(1, entry.get().prototype().getMaxStackSize()
                / Math.max(1, entry.get().count()))
                : 1;
        ShopExchange.Result result = ShopExchange.buy(who, entry.get(), wanted, storage);

        if (!result.success()) {
            // Quietly buying fewer than asked would spend credits the player
            // did not agree to spend, so a refusal stays a refusal.
            return false;
        }

        refreshBalance();
        broadcastChanges();
        return true;
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