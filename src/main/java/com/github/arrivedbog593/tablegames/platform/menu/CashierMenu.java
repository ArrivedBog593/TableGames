package com.github.arrivedbog593.tablegames.platform.menu;

import com.github.arrivedbog593.tablegames.engine.economy.TransactionType;
import com.github.arrivedbog593.tablegames.platform.economy.CreditExchange;
import com.github.arrivedbog593.tablegames.platform.economy.CreditStorage;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyEvents;
import com.github.arrivedbog593.tablegames.platform.economy.EconomyManager;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.economy.OutcomeSettler;
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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * The cashier's menu: a deposit tray, a balance, and a catalog to buy back
 * from.
 * <p>
 * Buying back does not happen here. It used to, through
 * {@code clickMenuButton}, which carries a single {@code int} and so had room
 * for a position in the catalog and nothing else. That position was never
 * stable — the catalog is sent sorted by value, so repricing any one item
 * reordered the whole list and a click already in flight arrived pointing
 * somewhere else. Buybacks now arrive as {@code CashierRedeemPayload}, keyed
 * by item id.
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

    /**
     * Panel geometry, shared with the screen.
     * <p>
     * Slots are positioned here and drawn there, so the two have to agree.
     * The old numbers came from a texture that fixed the panel at 176 wide,
     * which left nowhere to put a search field or a cart. Drawn from
     * rectangles, the size is a layout decision.
     */
    public static final int PANEL_WIDTH = 340;
    public static final int PANEL_HEIGHT = 284;

    /** The deposit tray, below the catalog rather than beside it. */
    public static final int TRAY_X = 8;
    public static final int TRAY_Y = 132;

    public static final int INVENTORY_Y = 201;
    public static final int HOTBAR_Y = 259;

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

    /**
     * What the player has riding on a round that has not settled.
     * <p>
     * The cashier can hand out items for credits, so it has to know what is
     * already promised elsewhere. Without this the screen offers a trade the
     * server then refuses, which reads as a broken button rather than as a
     * wager doing its job.
     */
    private final DataSlot committedLow = DataSlot.standalone();
    private final DataSlot committedHigh = DataSlot.standalone();

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
                        TRAY_X + column * 18, TRAY_Y + row * 18));
            }
        }

        // Centered under a panel that is no longer a fixed 176 wide.
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

        addDataSlot(balanceLow);
        addDataSlot(balanceHigh);
        addDataSlot(committedLow);
        addDataSlot(committedHigh);
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

    /** What of that balance is already promised to a live round. */
    public long committed() {
        return ((long) committedHigh.get() << 32) | (committedLow.get() & 0xFFFFFFFFL);
    }

    /** What the player can actually spend here. Never negative. */
    public long spendable() {
        return Math.max(0L, balance() - committed());
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

        long committed = OutcomeSettler.stakes().committedBy(player.getUUID());
        committedLow.set((int) (committed & 0xFFFFFFFFL));
        committedHigh.set((int) (committed >> 32));
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
        depositValue.set((int) trayValue());
    }

    /**
     * What the tray is worth, clamped the way the data slot carries it.
     * <p>
     * Clamped in one place rather than at each caller, so what the screen was
     * shown and what a conversion is checked against is the same number by
     * construction.
     */
    private long trayValue() {
        EconomyManager economy = EconomyEvents.economy();
        long total = 0;
        for (int i = 0; i < deposit.getContainerSize(); i++) {
            total += economy.valueOf(deposit.getItem(i)).orElse(0L);
        }
        return Math.min(Integer.MAX_VALUE, total);
    }

    // --- Actions ---------------------------------------------------------------

    /**
     * The outcome of converting the tray.
     *
     * @param success    whether anything was converted
     * @param failureKey translation key when nothing happened, else null
     * @param credits    credits paid out
     * @param expected   what the screen said the tray was worth
     * @param actual     what it is worth now
     */
    public record ConvertResult(boolean success, String failureKey,
                                long credits, long expected, long actual) {
    }

    /**
     * Turns everything in the tray into credits, slot by slot.
     * <p>
     * The figure on the screen was showing travels with the request and has to
     * agree. Without it, an administrator revaluing an item while somebody
     * had it sitting in the tray paid out whatever the new number was — the
     * player watched "+1,234" and was handed something else, in whichever
     * direction it moved. The window is short because the tray's value is a
     * data slot that resyncs every tick, but short is not closed.
     */
    public ConvertResult convertTray(ServerPlayer who, long expectedValue) {
        CreditStorage storage = storage();
        long actual = trayValue();
        if (actual <= 0) {
            return new ConvertResult(false, "tablegames.cashier.tray_empty", 0, 0, 0);
        }
        if (actual != expectedValue) {
            return new ConvertResult(false, "tablegames.cashier.tray_changed",
                    0, expectedValue, actual);
        }

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
            // Every slot refused, which the value check above should have
            // caught. Reported rather than swallowed.
            return new ConvertResult(false, "tablegames.cashier.tray_empty", 0, 0, 0);
        }
        deposit.setChanged();
        refreshBalance();
        broadcastChanges();
        return new ConvertResult(true, null, credits, expectedValue, actual);
    }

    // --- Housekeeping -----------------------------------------------------------

    @Override
    public void broadcastChanges() {
        refreshBalance();
        // The tray's worth is recomputed here and not only when an item
        // moves. Prices change under a tray nobody is touching — an
        // administrator revalues an item while somebody stands at the
        // cashier — and the figure used to sit at whatever it was when the
        // last item was dropped in, for as long as the screen stayed open.
        // Since the conversion now checks that figure, a stale one did not
        // merely mislead: it made the tray impossible to convert at all.
        // Nine slots a tick is nothing next to that.
        if (isServerSide()) {
            depositValue.set((int) trayValue());
        }
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
