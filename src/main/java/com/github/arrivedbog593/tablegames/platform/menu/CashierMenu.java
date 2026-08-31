package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.github.arrivedbog593.tablegames.platform.economy.CreditExchange;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyManager;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;
import com.github.arrivedbog593.tablegames.platform.registry.ModBlocks;
import com.github.arrivedbog593.tablegames.platform.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
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
 * The cashier's menu: a deposit tray, a balance, and a catalog to buy back
 * from.
 * <p>
 * Every action is validated on the server. The screen only draws and sends
 * button ids; it decides nothing. A client is free to claim anything, so
 * nothing it claims may move credits.
 * <p>
 * The deposit tray belongs to the menu, not the block. It is created when the
 * menu opens and emptied back into the player when it closes, so two people
 * at neighboring cashiers cannot reach each other's items, and a restart
 * cannot strand anything inside a block.
 * <p>
 * Data slots are written on the server only. Both sides run this class, and
 * the client's copy has no access to the credit store, so letting it compute
 * a balance would overwrite the synced value with zero the moment anything
 * moved.
 */
public class CashierMenu extends AbstractContainerMenu {

    public static final int DEPOSIT_ROWS = 3;
    public static final int DEPOSIT_COLUMNS = 3;
    public static final int DEPOSIT_SIZE = DEPOSIT_ROWS * DEPOSIT_COLUMNS;

    /** Button id: convert everything in the tray. */
    public static final int BUTTON_CONVERT = 0;

    /** Button ids at or above this redeem one of the catalog entries (id - base). */
    public static final int BUTTON_REDEEM_ONE = 1000;

    /** Button ids at or above this redeem a full stack of entry (id - base). */
    public static final int BUTTON_REDEEM_STACK = 2000;

    private final Container deposit = new SimpleContainer(DEPOSIT_SIZE) {
        @Override
        public void setChanged() {
            super.setChanged();
            slotsChanged(this);
        }
    };

    private final ContainerLevelAccess access;
    private final Player player;

    /**
     * Balance is long but data slots carry ints, so it travels in halves.
     * Casino balances legitimately pass two billion, and a silently truncated
     * balance is a support ticket nobody can explain.
     */
    private final DataSlot balanceLow = DataSlot.standalone();
    private final DataSlot balanceHigh = DataSlot.standalone();

    /** What the tray is currently worth, so the button can show it. */
    private final DataSlot depositValue = DataSlot.standalone();

    /** Client-side constructor: reads the block position the server wrote. */
    public CashierMenu(int containerId, Inventory inventory, FriendlyByteBuf buffer) {
        this(containerId, inventory, buffer.readBlockPos());
    }

    public CashierMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.CASHIER.get(), containerId);
        this.player = inventory.player;
        this.access = ContainerLevelAccess.create(inventory.player.level(), pos);

        for (int row = 0; row < DEPOSIT_ROWS; row++) {
            for (int column = 0; column < DEPOSIT_COLUMNS; column++) {
                addSlot(new DepositSlot(deposit, column + row * DEPOSIT_COLUMNS,
                        8 + column * 18, 28 + row * 18));
            }
        }

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
        addDataSlot(depositValue);
        refreshBalance();
    }

    /** Only convertible items may be put in the tray. */
    private final class DepositSlot extends Slot {
        private DepositSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            // The client has no conversion table, so it must not second-guess
            // the server here; it would refuse every item.
            return player.level().isClientSide
                    || EconomyEvents.economy().isConvertible(stack);
        }
    }

    // --- Synced values -------------------------------------------------------

    /** The player's balance, reassembled from its two halves. */
    public long balance() {
        return ((long) balanceHigh.get() << 32) | (balanceLow.get() & 0xFFFFFFFFL);
    }

    /** What the tray is worth right now. */
    public long depositValue() {
        return depositValue.get();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean isServerSide() {
        return !player.level().isClientSide && player.level().getServer() != null;
    }

    private void refreshBalance() {
        if (!isServerSide()) {
            // The client owns none of this; whatever it wrote here would be a
            // zero landing on top of a value the server just sent.
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
    public void slotsChanged(@NotNull Container container) {
        super.slotsChanged(container);
        if (container != deposit || !isServerSide()) {
            return;
        }
        EconomyManager economy = EconomyEvents.economy();
        long total = 0;
        for (int i = 0; i < deposit.getContainerSize(); i++) {
            total += economy.valueOf(deposit.getItem(i)).orElse(0L);
        }
        // Clamped because a data slot is an int; the tray cannot hold anything
        // like that much anyway.
        depositValue.set((int) Math.min(Integer.MAX_VALUE, total));
    }

    // --- Actions ---------------------------------------------------------------

    @Override
    public boolean clickMenuButton(Player who, int id) {
        if (who.level().isClientSide) {
            return true;
        }
        if (!(who instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        CreditStorage storage = storage();
        if (id == BUTTON_CONVERT) {
            return convertTray(serverPlayer, storage);
        }
        if (id >= BUTTON_REDEEM_STACK) {
            return redeem(serverPlayer, storage, id - BUTTON_REDEEM_STACK, true);
        }
        if (id >= BUTTON_REDEEM_ONE) {
            return redeem(serverPlayer, storage, id - BUTTON_REDEEM_ONE, false);
        }
        return false;
    }

    /** Turns everything in the tray into credits, slot by slot. */
    private boolean convertTray(ServerPlayer who, CreditStorage storage) {
        EconomyManager economy = EconomyEvents.economy();
        long credits = 0;

        for (int i = 0; i < deposit.getContainerSize(); i++) {
            ItemStack stack = deposit.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = ItemIds.idOf(stack);
            int count = stack.getCount();

            CreditExchange.Result result =
                    CreditExchange.deposit(who, stack, economy, storage);
            if (!result.success()) {
                continue;
            }
            deposit.setItem(i, ItemStack.EMPTY);
            credits += result.credits();
            EconomyEvents.record(storage, TransactionType.CONVERT_IN, who.getUUID(),
                    result.credits(), storage.balanceOf(who.getUUID()),
                    count + "x " + itemId + " (cashier)");
        }

        if (credits == 0) {
            return false;
        }
        deposit.setChanged();
        refreshBalance();
        broadcastChanges();
        return true;
    }

    /** Buys back one, or a stack, of a catalog entry. */
    private boolean redeem(ServerPlayer who, CreditStorage storage,
                           int entryIndex, boolean wholeStack) {
        var catalog = CashierCatalogPayload.current().entries();
        if (entryIndex < 0 || entryIndex >= catalog.size()) {
            return false;
        }
        String itemId = catalog.get(entryIndex).itemId();
        Optional<Item> item = ItemIds.item(itemId);
        if (item.isEmpty()) {
            return false;
        }

        long wanted = wholeStack ? new ItemStack(item.get()).getMaxStackSize() : 1;
        CreditExchange.Result result = CreditExchange.redeemExactly(
                who, item.get(), wanted, EconomyEvents.economy(), storage);

        if (!result.success()) {
            // Quietly redeeming fewer would spend credits the player did not
            // agree to spend, so a refusal stays a refusal.
            return false;
        }

        EconomyEvents.record(storage, TransactionType.CONVERT_OUT, who.getUUID(),
                -result.credits(), storage.balanceOf(who.getUUID()),
                result.itemCount() + "x " + itemId + " (cashier)");
        refreshBalance();
        broadcastChanges();
        return true;
    }

    // --- Housekeeping -----------------------------------------------------------

    @Override
    public void broadcastChanges() {
        refreshBalance();
        super.broadcastChanges();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(@NotNull Player who, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index < DEPOSIT_SIZE) {
            if (!moveItemStackTo(stack, DEPOSIT_SIZE, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, DEPOSIT_SIZE, false)) {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public void removed(@NotNull Player who) {
        super.removed(who);
        // Never swallow the tray. Anything left goes back to the player or on
        // the floor if they have no room.
        clearContainer(who, deposit);
    }

    @Override
    public boolean stillValid(@NotNull Player who) {
        return stillValid(access, who, ModBlocks.cashier());
    }
}