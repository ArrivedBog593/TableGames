package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyData;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.economy.ShopExchange;
import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;

/**
 * The shop's menu: a balance, a catalogue, and the player's own inventory.
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

    /** Button ids at or above this buy one of catalogue entry (id - base). */
    public static final int BUTTON_BUY_ONE = 1000;

    /** Button ids at or above this buy a full stack of entry (id - base). */
    public static final int BUTTON_BUY_STACK = 2000;

    private final ContainerLevelAccess access;
    private final Player player;

    /** Balance is a long; data slots carry ints, so it travels in halves. */
    private final DataSlot balanceLow = DataSlot.standalone();
    private final DataSlot balanceHigh = DataSlot.standalone();

    /** Client-side constructor: reads the block position the server wrote. */
    public ShopMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public ShopMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.SHOP.get(), containerId);
        this.player = inventory.player;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 122 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 180));
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
     * Buys from the catalogue.
     * <p>
     * The price comes from {@link EconomyData} rather than from the payload
     * the client was sent, so a stale or tampered catalogue cannot change what
     * anything costs.
     */
    private boolean buy(ServerPlayer who, CreditStorage storage,
                        int entryIndex, boolean wholeStack) {
        var catalog = ShopCatalogPayload.current(who.server).entries();
        if (entryIndex < 0 || entryIndex >= catalog.size()) {
            return false;
        }
        String itemId = catalog.get(entryIndex).itemId();

        Optional<Long> price = EconomyData.get(who.server).shopPriceOf(itemId);
        Optional<Item> item = ItemIds.item(itemId);
        if (price.isEmpty() || item.isEmpty()) {
            return false;
        }

        long wanted = wholeStack ? new ItemStack(item.get()).getMaxStackSize() : 1;
        ShopExchange.Result result =
                ShopExchange.buy(who, item.get(), wanted, price.get(), storage);

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