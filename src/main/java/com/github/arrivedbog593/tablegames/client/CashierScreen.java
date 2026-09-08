package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.economy.CreditExchange;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;
import com.github.arrivedbog593.tablegames.platform.network.CashierConvertPayload;
import com.github.arrivedbog593.tablegames.platform.network.CashierRedeemPayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cashier's screen: a tray for selling, a catalog for buying back.
 * <p>
 * The frame is drawn rather than blitted. The texture fixed this panel at a
 * hundred and seventy-six wide, which left nowhere for a search field on a
 * list that grows with every item an administrator prices, and nowhere at all
 * for a cart.
 * <p>
 * Buying back used to be one click for one item and shift for a stack, and
 * the click carried the row's <em>position</em>. The catalog arrives sorted
 * by value, so a single reprice reordered the whole list, and a click already
 * in flight bought something else entirely. Lines are keyed by item id now,
 * which the cashier's own data already treats as the identity.
 * <p>
 * Each line remembers the price it was quoted. Looking prices up live would
 * have the cart silently agree with every change the cashier makes, so a
 * player who decided to spend two thousand could press buy a moment after a
 * reprice and spend five without anything appearing to have happened.
 */
public class CashierScreen extends AbstractContainerScreen<CashierMenu> {

    private static final int COLUMNS = 3;
    private static final int ROWS = 4;
    private static final int CELL_WIDTH = 67;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 46;

    private static final int BALANCE_Y = 17;
    private static final int SEARCH_Y = 28;
    private static final int SEARCH_H = 14;
    /**
     * Narrower than the shop's, to fit the price-side button beside it.
     * <p>
     * The catalog here is a list of plain items, so a query is usually a word
     * or two — "diamante", "hierro" — where the shop has to reach named and
     * enchanted stacks. The room buys something the shop does not need.
     */
    private static final int SEARCH_W = 80;
    private static final int SORT_W = 62;

    /** Wide enough for the longer of the two labels with room to spare. */
    private static final int MODE_W = 47;
    private static final int TEXT_INSET = 4;
    private static final int TEXT_Y = (SEARCH_H - 8) / 2 + 1;

    /** The cart column, clear of the catalog's scroll arrows. */
    private static final int CART_X = 222;
    private static final int CART_W = 110;
    private static final int STEP_W = 16;
    private static final int QTY_Y = GRID_Y;
    private static final int ADD_Y = QTY_Y + SEARCH_H + 4;
    private static final int CART_Y = ADD_Y + SEARCH_H + 6;
    private static final int CART_ROWS = 3;
    private static final int CART_ROW_H = 19;
    private static final int TOTAL_Y = CART_Y + CART_ROWS * CART_ROW_H + 4;
    private static final int BUY_Y = TOTAL_Y + 10;

    /** The convert button, beside the tray and vertically centered on it. */
    private static final int CONVERT_X = 70;
    private static final int CONVERT_W = 100;
    private static final int CONVERT_H = 18;
    private static final int CONVERT_Y = CashierMenu.TRAY_Y
            + (CashierMenu.DEPOSIT_ROWS * 18 - CONVERT_H) / 2;

    /** Nothing picked. An item id is never empty. */
    private static final String NO_SELECTION = "";

    /**
     * The palette, shared with every other screen in the mod.
     * <p>
     * Green does the thing, brown undoes one, red means the undoing is armed,
     * amber means something changed and wants looking at.
     */
    private static final int ACTION_FACE = 0xFF2E7D32;
    private static final int REMOVE_FACE = 0xFF6D4C41;
    private static final int ARMED_FACE = 0xFFB71C1C;
    private static final int STALE_FACE = 0xFFB07B18;
    private static final int DISABLED_FACE = 0xFF4A4A4A;
    private static final int CONTROL_FACE = 0xFF555555;
    private static final int PICKED_RING = 0xFFE0B33A;

    /**
     * Prices the cashier pays out rather than charges.
     * <p>
     * Green, like the balance: credits coming in. Deliberately not the amber
     * a stale quote uses, which means something else entirely.
     */
    private static final int SALE_TEXT = 0xFF81C784;

    private static final long CONFIRM_MILLIS = 3_000;
    private static final long PENDING_MILLIS = 5_000;

    /** Mirrors the server's caps, so the screen refuses before the packet does. */
    private static final int MAX_LINES = CreditExchange.MAX_LINES;
    private static final int MAX_COUNT = CreditExchange.MAX_COUNT;

    private int scroll;
    private int cartScroll;

    /** The catalog item picked from the grid, or {@link #NO_SELECTION}. */
    private String selected = NO_SELECTION;

    /** The cart line picked, or {@link #NO_SELECTION}. Exclusive with the above. */
    private String cartSelected = NO_SELECTION;

    private long confirmUntil;
    private long confirmBuyUntil;
    private long pendingSince;
    private long balanceAtSend;
    private boolean suppressQuantity;

    /**
     * Which side of the spread the grid is quoting.
     * <p>
     * The cashier trades in both directions at two different prices, and one
     * number in a cell cannot say which one it is. A diamond reading 123 next
     * to a tray button reading +111 is the same diamond twice, and nothing on
     * screen explained the gap.
     * <p>
     * Buying is the default because that is what the grid <em>does</em>: the
     * selling price is here to be looked up, not acted on, since selling
     * happens through the tray.
     */
    private boolean showSalePrice;

    /**
     * What the tray was worth when the player last put something in it.
     * <p>
     * Frozen for the same reason a cart line is: reading it live means the
     * button quietly agrees with every revaluation, and somebody who put
     * sixty-four blocks in expecting ninety-six thousand converts them for
     * sixty-four without anything appearing to have happened.
     */
    private long quotedTrayValue;

    /**
     * What the tray held the last frame, as a cheap signature.
     * <p>
     * A change here is the player handling their own tray, which is the one
     * case where a new figure needs no announcing. A change in the value
     * without one is a price moving underneath them, which does.
     */
    private int trayShape = Integer.MIN_VALUE;

    /**
     * How long after the tray is handled, the quote keeps following the server.
     * <p>
     * The slot changes on this client the instant an item is dropped in; the
     * value catches up a tick later, from the server. Without a moment's
     * grace the screen would freeze the figure from before the drop and then
     * announce the correct one as a change.
     */
    private static final long TRAY_GRACE_MILLIS = 500;
    private long trayGraceUntil;

    /** One line of the cart: how many, and what one cost when it was added. */
    private record CartLine(int count, long quotedUnitPrice) {
    }

    /** Keyed by item id, which is the identity the cashier's table already uses. */
    private final Map<String, CartLine> cart = new LinkedHashMap<>();

    /**
     * Price and name only.
     * <p>
     * The cashier has no catalog numbers — it prices by item id, one value
     * per id — so "catalog order" would name something the data does not
     * have. Sorted by the buyback, which orders identically to the value: the
     * surcharge is a percentage, so it cannot reshuffle anything.
     */
    private final CatalogView<CashierCatalogPayload.Entry> catalog = new CatalogView<>(
            entry -> ItemIds.item(entry.itemId()).map(ItemStack::new).orElse(ItemStack.EMPTY),
            CashierCatalogPayload.Entry::buyback,
            List.of(CatalogView.SortBy.PRICE, CatalogView.SortBy.NAME));

    private EditBox search;
    private EditBox quantity;

    public CashierScreen(CashierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = CashierMenu.PANEL_WIDTH;
        this.imageHeight = CashierMenu.PANEL_HEIGHT;
        this.inventoryLabelY = CashierMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();

        String carriedSearch = search == null ? "" : search.getValue();
        search = new EditBox(font, leftPos + GRID_X + TEXT_INSET, topPos + SEARCH_Y + TEXT_Y,
                SEARCH_W - TEXT_INSET * 2, 8, Component.translatable("tablegames.shop.search"));
        search.setBordered(false);
        search.setHint(Component.translatable("tablegames.shop.search"));
        search.setResponder(text -> {
            catalog.setQuery(text);
            scroll = 0;
        });
        search.setValue(carriedSearch);
        addRenderableWidget(search);

        String carriedQuantity = quantity == null ? "1" : quantity.getValue();
        quantity = new EditBox(font, leftPos + CART_X + STEP_W + 6, topPos + QTY_Y + TEXT_Y,
                CART_W - 2 * STEP_W - 12, 8, Component.translatable("tablegames.cart.quantity"));
        quantity.setBordered(false);
        quantity.setFilter(text -> text.isEmpty() || text.matches("\\d{1,5}"));
        // Typing edits the picked line as it is typed, so there is no third
        // button for applying a number the player can already see.
        quantity.setResponder(text -> {
            if (suppressQuantity || cartSelected.equals(NO_SELECTION)) {
                return;
            }
            int typed = quantityTyped();
            if (typed > 0) {
                requote(cartSelected, typed);
            }
        });
        quantity.setValue(carriedQuantity);
        addRenderableWidget(quantity);

        catalog.restore(ScreenPreferences.cashierSort(),
                ScreenPreferences.cashierSortDescending());
        showSalePrice = ScreenPreferences.cashierShowSalePrice();
    }

    // --- Drawing ---------------------------------------------------------------

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick,
                            int mouseX, int mouseY) {
        Panels.panel(graphics, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        Panels.recess(graphics, leftPos + GRID_X - 1, topPos + GRID_Y - 1,
                COLUMNS * CELL_WIDTH + 2, ROWS * CELL_HEIGHT + 2);
        Panels.recess(graphics, leftPos + CART_X - 1, topPos + CART_Y - 1,
                CART_W + 2, CART_ROWS * CART_ROW_H + 2);

        for (int row = 0; row < CashierMenu.DEPOSIT_ROWS; row++) {
            for (int column = 0; column < CashierMenu.DEPOSIT_COLUMNS; column++) {
                Panels.slot(graphics, leftPos + CashierMenu.TRAY_X + column * 18,
                        topPos + CashierMenu.TRAY_Y + row * 18);
            }
        }

        int inventoryLeft = leftPos + (imageWidth - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                Panels.slot(graphics, inventoryLeft + column * 18,
                        topPos + CashierMenu.INVENTORY_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            Panels.slot(graphics, inventoryLeft + column * 18, topPos + CashierMenu.HOTBAR_Y);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        settlePending();
        settleTray();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCatalog(graphics, mouseX, mouseY);
        renderCart(graphics, mouseX, mouseY);
        renderConvertButton(graphics, mouseX, mouseY);
        renderTooltip(graphics, mouseX, mouseY);
    }

    /** Decides whether the tray's figure is the player's or the cashier's doing. */
    private void settleTray() {
        int shape = trayShape();
        if (shape != trayShape) {
            trayShape = shape;
            trayGraceUntil = System.currentTimeMillis() + TRAY_GRACE_MILLIS;
        }
        if (System.currentTimeMillis() < trayGraceUntil) {
            quotedTrayValue = menu.depositValue();
        }
    }

    /** What the tray holds collapsed to one number. Identity, not worth. */
    private int trayShape() {
        int shape = 1;
        for (int i = 0; i < CashierMenu.DEPOSIT_SIZE; i++) {
            Slot slot = menu.slots.get(i);
            ItemStack stack = slot.getItem();
            shape = shape * 31 + (stack.isEmpty()
                    ? 0 : stack.getItem().getDescriptionId().hashCode());
            shape = shape * 31 + stack.getCount();
        }
        return shape;
    }

    private void settlePending() {
        if (pendingSince == 0) {
            return;
        }
        if (menu.balance() != balanceAtSend) {
            cart.clear();
            cartScroll = 0;
            cartSelected = NO_SELECTION;
            confirmUntil = 0;
            confirmBuyUntil = 0;
            pendingSince = 0;
            return;
        }
        if (System.currentTimeMillis() - pendingSince > PENDING_MILLIS) {
            pendingSince = 0;
        }
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, Panels.LABEL_TEXT, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, Panels.LABEL_TEXT, false);
        // Spendable first, then the whole balance: the credits are still
        // theirs, they are just promised to a round that has not settled.
        graphics.drawString(font, menu.committed() > 0
                        ? Component.translatable("tablegames.cashier.balance_committed",
                        format(menu.spendable()), format(menu.balance()))
                        : Component.translatable("tablegames.cashier.balance",
                        format(menu.balance())),
                GRID_X, BALANCE_Y, 0x2E7D32, false);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        // Refreshed before anything is drawn, so the grid, the tooltip and the
        // click handler all read the same list.
        catalog.accept(ClientCashierState.entries());
        List<CashierCatalogPayload.Entry> entries = catalog.entries();

        if (entries.isEmpty()) {
            Component message = Component.translatable(catalog.isFiltered()
                    ? "tablegames.shop.no_matches" : "tablegames.cashier.no_items");
            graphics.drawString(font, message,
                    leftPos + GRID_X + (COLUMNS * CELL_WIDTH - font.width(message)) / 2,
                    topPos + GRID_Y + 10, 0xFFE8E8E8, true);
            drawControls(graphics, mouseX, mouseY);
            return;
        }

        int first = scroll * COLUMNS;
        for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
            int index = first + cell;
            if (index >= entries.size()) {
                break;
            }
            CashierCatalogPayload.Entry entry = entries.get(index);
            int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
            int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;

            boolean hovered = isOver(mouseX, mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2);
            boolean picked = entry.itemId().equals(selected);
            // Spendable, not balance: what is riding on a live round is
            // already promised to it.
            boolean affordable = menu.spendable() >= entry.buyback();

            graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                    hovered ? 0x50FFFFFF : 0x20000000);
            if (picked) {
                graphics.renderOutline(x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2, PICKED_RING);
            }

            Optional<Item> item = ItemIds.item(entry.itemId());
            item.ifPresent(found -> graphics.renderItem(new ItemStack(found), x + 2, y + 2));

            // Green for what the cashier pays out, white for what it charges.
            // Color as well as a label, because the number moves between two
            // meanings and a player glancing at a grid reads the figure long
            // before they read the button that decided which figure it is.
            graphics.drawString(font,
                    compact(showSalePrice ? entry.value() : entry.buyback()),
                    x + 21, y + 6,
                    showSalePrice ? SALE_TEXT : affordable ? 0xFFFFFF : 0xFFFF6B6B, false);
        }

        drawControls(graphics, mouseX, mouseY);

        int totalRows = (entries.size() + COLUMNS - 1) / COLUMNS;
        if (scroll > 0) {
            graphics.drawString(font, "▲", leftPos + GRID_X + COLUMNS * CELL_WIDTH + 2,
                    topPos + GRID_Y, Panels.LABEL_TEXT, false);
        }
        if (scroll + ROWS < totalRows) {
            graphics.drawString(font, "▼", leftPos + GRID_X + COLUMNS * CELL_WIDTH + 2,
                    topPos + GRID_Y + ROWS * CELL_HEIGHT - 9, Panels.LABEL_TEXT, false);
        }
    }

    /** The search field and the sort button, drawn for both catalog states. */
    private void drawControls(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean typing = search != null && search.isFocused();
        drawControl(graphics, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);

        int sortX = sortButtonX();
        int y = topPos + SEARCH_Y;
        drawControl(graphics, sortX, y, SORT_W, Panels.OUTLINE, CONTROL_FACE,
                isOver(mouseX, mouseY, sortX, y, SORT_W, SEARCH_H));
        Component label = Component.literal(catalog.descending() ? "▼ " : "▲ ")
                .append(catalog.sortBy().label());
        graphics.drawString(font, label, sortX + (SORT_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);

        int modeX = modeButtonX();
        drawControl(graphics, modeX, y, MODE_W, Panels.OUTLINE, CONTROL_FACE,
                isOver(mouseX, mouseY, modeX, y, MODE_W, SEARCH_H));
        Component mode = Component.translatable(showSalePrice
                ? "tablegames.cashier.mode_sell" : "tablegames.cashier.mode_buy");
        graphics.drawString(font, mode, modeX + (MODE_W - font.width(mode)) / 2,
                y + TEXT_Y, showSalePrice ? SALE_TEXT : 0xFFFFFFFF, false);

        if (search != null) {
            search.render(graphics, mouseX, mouseY, 0f);
        }
    }

    private int modeButtonX() {
        return sortButtonX() + SORT_W + 4;
    }

    private void renderCart(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean editing = !cartSelected.equals(NO_SELECTION);
        boolean hasPick = editing || entryById(selected) != null;

        int minusX = leftPos + CART_X;
        int plusX = leftPos + CART_X + CART_W - STEP_W;
        int qtyY = topPos + QTY_Y;
        boolean typingQuantity = quantity != null && quantity.isFocused();

        drawStep(graphics, minusX, qtyY, "-", hasPick,
                isOver(mouseX, mouseY, minusX, qtyY, STEP_W, SEARCH_H));
        drawControl(graphics, leftPos + CART_X + STEP_W + 2, qtyY, CART_W - 2 * STEP_W - 4,
                typingQuantity ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typingQuantity);
        drawStep(graphics, plusX, qtyY, "+", hasPick,
                isOver(mouseX, mouseY, plusX, qtyY, STEP_W, SEARCH_H));
        if (quantity != null) {
            quantity.render(graphics, mouseX, mouseY, 0f);
        }

        // One button doing two jobs, because the two are never available at
        // once: a line is either being built or being edited.
        int actionY = topPos + ADD_Y;
        boolean armed = System.currentTimeMillis() < confirmUntil;
        Component actionLabel = editing
                ? Component.translatable(armed
                ? "tablegames.cart.remove_confirm" : "tablegames.cart.remove")
                : Component.translatable("tablegames.cart.add");
        boolean canAct = editing
                || (hasPick && quantityTyped() > 0 && cart.size() < MAX_LINES);
        drawButton(graphics, leftPos + CART_X, actionY, actionLabel, canAct,
                isOver(mouseX, mouseY, leftPos + CART_X, actionY, CART_W, SEARCH_H),
                editing ? (armed ? ARMED_FACE : REMOVE_FACE) : ACTION_FACE);

        if (cart.isEmpty()) {
            Component empty = Component.translatable("tablegames.cart.empty_hint");
            graphics.drawString(font, empty,
                    leftPos + CART_X + (CART_W - font.width(empty)) / 2,
                    topPos + CART_Y + 6, 0xFFE8E8E8, true);
        } else {
            List<String> ids = new ArrayList<>(cart.keySet());
            for (int row = 0; row < CART_ROWS; row++) {
                int index = cartScroll + row;
                if (index >= ids.size()) {
                    break;
                }
                String itemId = ids.get(index);
                drawCartLine(graphics, mouseX, mouseY, itemId, cart.get(itemId),
                        topPos + CART_Y + row * CART_ROW_H);
            }
            if (cartScroll > 0) {
                graphics.drawString(font, "▲", leftPos + CART_X + CART_W - 7,
                        topPos + CART_Y + 1, Panels.LABEL_TEXT, false);
            }
            if (cartScroll + CART_ROWS < ids.size()) {
                graphics.drawString(font, "▼", leftPos + CART_X + CART_W - 7,
                        topPos + CART_Y + CART_ROWS * CART_ROW_H - 9, Panels.LABEL_TEXT, false);
            }
        }

        // Dark on the panel's own light gray, like every other label sitting
        // directly on it.
        long total = cartTotal();
        Component totalLine = total < 0
                ? Component.translatable("tablegames.cart.unavailable")
                : Component.translatable("tablegames.cart.total", format(total));
        graphics.drawString(font, totalLine, leftPos + CART_X, topPos + TOTAL_Y,
                total < 0 || total > menu.spendable() ? 0xFF9C1C1C : Panels.LABEL_TEXT, false);

        int buyY = topPos + BUY_Y;
        boolean stale = hasStaleQuotes();
        boolean buyArmed = System.currentTimeMillis() < confirmBuyUntil;
        Component buyLabel = stale
                ? Component.translatable("tablegames.cart.accept_prices")
                : Component.translatable(buyArmed
                ? "tablegames.cart.buy_confirm" : "tablegames.cart.buy");
        drawButton(graphics, leftPos + CART_X, buyY, buyLabel,
                stale ? !cart.isEmpty() : canBuy(),
                isOver(mouseX, mouseY, leftPos + CART_X, buyY, CART_W, SEARCH_H),
                stale ? STALE_FACE : ACTION_FACE);
    }

    private void drawCartLine(GuiGraphics graphics, int mouseX, int mouseY,
                              String itemId, CartLine line, int y) {
        int x = leftPos + CART_X;
        boolean hovered = isOver(mouseX, mouseY, x, y, CART_W, CART_ROW_H - 1);
        graphics.fill(x, y, x + CART_W, y + CART_ROW_H - 1,
                hovered ? 0x50FFFFFF : 0x20000000);
        if (itemId.equals(cartSelected)) {
            graphics.renderOutline(x, y, CART_W, CART_ROW_H - 1, PICKED_RING);
        }

        CashierCatalogPayload.Entry entry = entryById(itemId);
        if (entry == null) {
            // Stopped being convertible while it sat in the cart. Drawn rather
            // than dropped, so the player can see why the till is refusing.
            graphics.drawString(font, Component.translatable("tablegames.cart.gone"),
                    x + 2, y + 6, 0xFFFF6B6B, false);
            return;
        }

        ItemIds.item(itemId).ifPresent(found ->
                graphics.renderItem(new ItemStack(found), x + 1, y + 1));
        graphics.drawString(font, "x" + line.count(), x + 20, y + 6, 0xFFFFFFFF, false);

        // The quoted subtotal, amber when the cashier has moved underneath it.
        boolean stale = entry.buyback() != line.quotedUnitPrice();
        String subtotal = compact(line.quotedUnitPrice() * (long) line.count());
        graphics.drawString(font, subtotal, x + CART_W - 9 - font.width(subtotal), y + 6,
                stale ? PICKED_RING : 0xFFCFCFCF, false);
    }

    private void renderConvertButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + CONVERT_X;
        int y = topPos + CONVERT_Y;
        boolean enabled = quotedTrayValue > 0;
        boolean stale = trayIsStale();
        boolean hovered = isOver(mouseX, mouseY, x, y, CONVERT_W, CONVERT_H);

        Panels.button(graphics, x, y, CONVERT_W, CONVERT_H,
                !enabled ? DISABLED_FACE : stale ? STALE_FACE : ACTION_FACE,
                enabled && hovered);
        Component label;
        if (!enabled) {
            label = Component.translatable("tablegames.cashier.convert");
        } else if (stale) {
            label = Component.translatable("tablegames.cashier.tray_stale");
        } else {
            label = Component.literal("+" + format(quotedTrayValue));
        }
        graphics.drawString(font, label, x + (CONVERT_W - font.width(label)) / 2,
                y + (CONVERT_H - 8) / 2, enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    /** Whether the cashier has revalued something sitting in the tray. */
    private boolean trayIsStale() {
        return quotedTrayValue > 0 && menu.depositValue() != quotedTrayValue;
    }

    private int sortButtonX() {
        return leftPos + GRID_X + SEARCH_W + 4;
    }

    private void drawControl(GuiGraphics graphics, int x, int y, int width,
                             int outline, int face, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + SEARCH_H + 1, outline);
        graphics.fill(x, y, x + width, y + SEARCH_H, hovered ? face + 0x00191919 : face);
    }

    private void drawButton(GuiGraphics graphics, int x, int y,
                            Component label, boolean enabled, boolean hovered, int face) {
        drawControl(graphics, x, y, CashierScreen.CART_W, Panels.OUTLINE,
                enabled ? face : DISABLED_FACE, enabled && hovered);
        graphics.drawString(font, label, x + (CashierScreen.CART_W - font.width(label)) / 2,
                y + TEXT_Y, enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    private void drawStep(GuiGraphics graphics, int x, int y, String glyph,
                          boolean enabled, boolean hovered) {
        drawControl(graphics, x, y, STEP_W, Panels.OUTLINE,
                enabled ? CONTROL_FACE : DISABLED_FACE, enabled && hovered);
        graphics.drawString(font, glyph, x + (STEP_W - font.width(glyph)) / 2,
                y + TEXT_Y, enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    // --- Tooltips ---------------------------------------------------------------

    @Override
    protected void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, sortButtonX(), topPos + SEARCH_Y, SORT_W, SEARCH_H)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("tablegames.shop.sort_title"));
            lines.add(Component.translatable("tablegames.shop.sort_hint")
                    .withStyle(ChatFormatting.GRAY));
            for (CatalogView.SortBy option : catalog.options()) {
                lines.add(Component.translatable("tablegames.shop.sort_option", option.label(),
                                Component.translatable("tablegames.sort."
                                        + option.name().toLowerCase(Locale.ROOT) + ".describe"))
                        .withStyle(option == catalog.sortBy()
                                ? ChatFormatting.WHITE : ChatFormatting.DARK_GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        if (isOver(mouseX, mouseY, modeButtonX(), topPos + SEARCH_Y, MODE_W, SEARCH_H)) {
            graphics.renderComponentTooltip(font, List.of(
                            Component.translatable(showSalePrice
                                    ? "tablegames.cashier.mode_sell_title"
                                    : "tablegames.cashier.mode_buy_title"),
                            Component.translatable("tablegames.cashier.mode_hint")
                                    .withStyle(ChatFormatting.GRAY),
                            // Said plainly, because the grid changes what it
                            // quotes and the cart does not.
                            Component.translatable("tablegames.cashier.mode_cart_note")
                                    .withStyle(ChatFormatting.DARK_GRAY)),
                    mouseX, mouseY);
            return;
        }

        if (isOver(mouseX, mouseY, leftPos + CONVERT_X, topPos + CONVERT_Y,
                CONVERT_W, CONVERT_H)) {
            graphics.renderComponentTooltip(font, trayIsStale()
                            ? List.of(Component.translatable("tablegames.cashier.tray_stale"),
                            Component.translatable("tablegames.cashier.tray_stale_hint",
                                            format(quotedTrayValue),
                                            format(menu.depositValue()))
                                    .withStyle(ChatFormatting.GRAY))
                            : List.of(Component.translatable("tablegames.cashier.convert"),
                            Component.translatable("tablegames.cashier.convert_hint")
                                    .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return;
        }

        if (renderCartTooltip(graphics, mouseX, mouseY)) {
            return;
        }

        List<CashierCatalogPayload.Entry> entries = catalog.entries();
        int first = scroll * COLUMNS;
        for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
            int index = first + cell;
            if (index >= entries.size()) {
                break;
            }
            int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
            int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;
            if (!isOver(mouseX, mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2)) {
                continue;
            }
            List<Component> lines = priceLines(entries.get(index));
            lines.add(Component.translatable("tablegames.cart.select_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    /** The name and both directions of an entry's price. */
    private List<Component> priceLines(CashierCatalogPayload.Entry entry) {
        List<Component> lines = new ArrayList<>();
        lines.add(ItemIds.displayName(entry.itemId()));
        if (entry.buyback() == entry.value()) {
            lines.add(Component.translatable("tablegames.cashier.unit_price",
                    format(entry.value())).withStyle(ChatFormatting.GRAY));
        } else {
            // Both directions, separately, once they stop matching. The grid
            // buys items, so quoting only what selling one pays puts the wrong
            // number next to the button that spends it.
            lines.add(Component.translatable("tablegames.cashier.buy_price",
                    format(entry.buyback())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tablegames.cashier.sell_price",
                    format(entry.value())).withStyle(ChatFormatting.DARK_GRAY));
        }
        return lines;
    }

    /** @return true when the cursor was over something in the cart column */
    private boolean renderCartTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!cartSelected.equals(NO_SELECTION)
                && isOver(mouseX, mouseY, leftPos + CART_X, topPos + ADD_Y, CART_W, SEARCH_H)) {
            graphics.renderComponentTooltip(font, List.of(
                            Component.translatable("tablegames.cart.remove"),
                            Component.translatable("tablegames.cart.remove_hint")
                                    .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return true;
        }

        if (isOver(mouseX, mouseY, leftPos + CART_X, topPos + BUY_Y, CART_W, SEARCH_H)) {
            if (hasStaleQuotes()) {
                graphics.renderComponentTooltip(font, List.of(
                                Component.translatable("tablegames.cart.accept_prices"),
                                Component.translatable("tablegames.cart.accept_prices_hint")
                                        .withStyle(ChatFormatting.GRAY)),
                        mouseX, mouseY);
                return true;
            }
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("tablegames.cart.buy"));
            long total = cartTotal();
            if (pendingSince != 0) {
                lines.add(Component.translatable("tablegames.cart.buy_pending")
                        .withStyle(ChatFormatting.GRAY));
            } else if (cart.isEmpty()) {
                lines.add(Component.translatable("tablegames.cart.empty_hint")
                        .withStyle(ChatFormatting.GRAY));
            } else if (total < 0) {
                lines.add(Component.translatable("tablegames.cart.unavailable")
                        .withStyle(ChatFormatting.RED));
            } else if (total > menu.spendable()) {
                lines.add(Component.translatable("tablegames.cart.short",
                        format(menu.spendable()), format(total)).withStyle(ChatFormatting.RED));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        }

        List<String> ids = new ArrayList<>(cart.keySet());
        for (int row = 0; row < CART_ROWS; row++) {
            int index = cartScroll + row;
            if (index >= ids.size()) {
                break;
            }
            int y = topPos + CART_Y + row * CART_ROW_H;
            if (!isOver(mouseX, mouseY, leftPos + CART_X, y, CART_W, CART_ROW_H - 1)) {
                continue;
            }
            String itemId = ids.get(index);
            CashierCatalogPayload.Entry entry = entryById(itemId);
            CartLine line = cart.get(itemId);
            List<Component> lines = new ArrayList<>();
            if (entry == null) {
                lines.add(Component.translatable("tablegames.cart.gone")
                        .withStyle(ChatFormatting.RED));
            } else if (line != null && entry.buyback() != line.quotedUnitPrice()) {
                lines.add(ItemIds.displayName(itemId));
                lines.add(Component.translatable("tablegames.cart.quote_changed",
                                format(line.quotedUnitPrice()), format(entry.buyback()))
                        .withStyle(ChatFormatting.GOLD));
            } else {
                lines.addAll(priceLines(entry));
            }
            lines.add(Component.translatable("tablegames.cart.edit_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        return false;
    }

    // --- Input ------------------------------------------------------------------

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox field : new EditBox[]{search, quantity}) {
            if (field == null || !field.isFocused()) {
                continue;
            }
            if (keyCode == InputConstants.KEY_ESCAPE) {
                field.setFocused(false);
                setFocused(null);
                return true;
            }
            if (field == quantity && cartSelected.equals(NO_SELECTION)
                    && (keyCode == InputConstants.KEY_RETURN
                    || keyCode == InputConstants.KEY_NUMPADENTER)) {
                addToCart();
                return true;
            }
            // A letter typed into a search box is a letter, not the inventory
            // key. Without this, the screen closes on "e" halfway through a word.
            if (field.keyPressed(keyCode, scanCode, modifiers) || field.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = (int) mouseX;
        int y = (int) mouseY;

        if (search != null && isOver(x, y, leftPos + GRID_X, topPos + SEARCH_Y,
                SEARCH_W, SEARCH_H)) {
            if (button == 1) {
                search.setValue("");
                return true;
            }
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (isOver(x, y, sortButtonX(), topPos + SEARCH_Y, SORT_W, SEARCH_H)) {
            if (button == 1) {
                catalog.toggleDirection();
            } else {
                catalog.cycleSort();
            }
            ScreenPreferences.setCashierSort(catalog.sortBy(), catalog.descending());
            scroll = 0;
            playClick();
            return true;
        }
        if (isOver(x, y, modeButtonX(), topPos + SEARCH_Y, MODE_W, SEARCH_H)) {
            showSalePrice = !showSalePrice;
            ScreenPreferences.setCashierShowSalePrice(showSalePrice);
            playClick();
            return true;
        }
        if (quotedTrayValue > 0
                && isOver(x, y, leftPos + CONVERT_X, topPos + CONVERT_Y, CONVERT_W, CONVERT_H)) {
            if (trayIsStale()) {
                // Accepting the new figure and converting at it are two
                // presses, so the number is seen before it is agreed to.
                quotedTrayValue = menu.depositValue();
            } else {
                // The figure on the button travels with the request, so the
                // server can refuse a tray that moved in the meantime.
                PacketDistributor.sendToServer(new CashierConvertPayload(quotedTrayValue));
            }
            playClick();
            return true;
        }

        if (clickedCart(x, y)) {
            return true;
        }
        if (button == 0 && clickedCatalog(x, y)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean clickedCatalog(int mouseX, int mouseY) {
        List<CashierCatalogPayload.Entry> entries = catalog.entries();
        int first = scroll * COLUMNS;
        for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
            int index = first + cell;
            if (index >= entries.size()) {
                break;
            }
            int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
            int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;
            if (!isOver(mouseX, mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2)) {
                continue;
            }
            String itemId = entries.get(index).itemId();
            selected = itemId.equals(selected) ? NO_SELECTION : itemId;
            // One subject at a time, or the stepper edits the cart while the
            // grid says it is adding.
            cartSelected = NO_SELECTION;
            confirmUntil = 0;
            setQuantitySilently("1");
            playClick();
            return true;
        }
        return false;
    }

    private boolean clickedCart(int mouseX, int mouseY) {
        int minusX = leftPos + CART_X;
        int plusX = leftPos + CART_X + CART_W - STEP_W;
        int qtyY = topPos + QTY_Y;

        if (isOver(mouseX, mouseY, minusX, qtyY, STEP_W, SEARCH_H)) {
            step(-1);
            return true;
        }
        if (isOver(mouseX, mouseY, plusX, qtyY, STEP_W, SEARCH_H)) {
            step(1);
            return true;
        }
        if (quantity != null && isOver(mouseX, mouseY, leftPos + CART_X + STEP_W + 2, qtyY,
                CART_W - 2 * STEP_W - 4, SEARCH_H)) {
            setFocused(quantity);
            quantity.setFocused(true);
            return true;
        }
        if (isOver(mouseX, mouseY, leftPos + CART_X, topPos + ADD_Y, CART_W, SEARCH_H)) {
            if (cartSelected.equals(NO_SELECTION)) {
                addToCart();
            } else {
                removePicked();
            }
            return true;
        }
        if (isOver(mouseX, mouseY, leftPos + CART_X, topPos + BUY_Y, CART_W, SEARCH_H)) {
            if (hasStaleQuotes()) {
                acceptNewPrices();
            } else {
                buy();
            }
            return true;
        }

        List<String> ids = new ArrayList<>(cart.keySet());
        for (int row = 0; row < CART_ROWS; row++) {
            int index = cartScroll + row;
            if (index >= ids.size()) {
                break;
            }
            int y = topPos + CART_Y + row * CART_ROW_H;
            if (!isOver(mouseX, mouseY, leftPos + CART_X, y, CART_W, CART_ROW_H - 1)) {
                continue;
            }
            pickCartLine(ids.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isOver((int) mouseX, (int) mouseY, leftPos + CART_X, topPos + CART_Y,
                CART_W, CART_ROWS * CART_ROW_H)) {
            cartScroll -= (int) Math.signum(deltaY);
            clampCartScroll();
            return true;
        }
        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, totalRows - ROWS);
        scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, maxScroll);
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    // --- The cart ----------------------------------------------------------------

    private void pickCartLine(String itemId) {
        confirmUntil = 0;
        if (cartSelected.equals(itemId)) {
            cartSelected = NO_SELECTION;
        } else {
            cartSelected = itemId;
            selected = NO_SELECTION;
            CartLine line = cart.get(itemId);
            setQuantitySilently(String.valueOf(line == null ? 1 : line.count()));
        }
        playClick();
    }

    /** Takes the picked line out, on the second click. */
    private void removePicked() {
        if (cartSelected.equals(NO_SELECTION)) {
            return;
        }
        if (System.currentTimeMillis() >= confirmUntil) {
            confirmUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
            playClick();
            return;
        }
        cart.remove(cartSelected);
        cartSelected = NO_SELECTION;
        confirmUntil = 0;
        confirmBuyUntil = 0;
        clampCartScroll();
        playClick();
    }

    private void addToCart() {
        int count = quantityTyped();
        // Resolved once and kept, so the null check covers the value used.
        CashierCatalogPayload.Entry entry = entryById(selected);
        if (entry == null || count <= 0) {
            return;
        }
        if (!cart.containsKey(selected) && cart.size() >= MAX_LINES) {
            return;
        }
        CartLine existing = cart.get(selected);
        int total = existing == null
                ? count
                : Math.clamp((long) existing.count() + count, 1, MAX_COUNT);
        cart.put(selected, new CartLine(total, entry.buyback()));
        // A cart that changed is a cart the player has not agreed to yet.
        confirmBuyUntil = 0;
        playClick();
    }

    /** Changes a line's count without disturbing the price it was quoted. */
    private void requote(String itemId, int count) {
        CartLine line = cart.get(itemId);
        if (line != null) {
            cart.put(itemId, new CartLine(count, line.quotedUnitPrice()));
            confirmBuyUntil = 0;
        }
    }

    /** Accepts the cashier's current prices for every line. */
    private void acceptNewPrices() {
        for (Map.Entry<String, CartLine> line : new ArrayList<>(cart.entrySet())) {
            CashierCatalogPayload.Entry entry = entryById(line.getKey());
            if (entry != null) {
                cart.put(line.getKey(), new CartLine(line.getValue().count(), entry.buyback()));
            }
        }
        confirmBuyUntil = 0;
        playClick();
    }

    /** Sends the cart, on the second press. */
    private void buy() {
        if (!canBuy()) {
            return;
        }
        if (System.currentTimeMillis() >= confirmBuyUntil) {
            confirmBuyUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
            playClick();
            return;
        }
        confirmBuyUntil = 0;

        List<CashierRedeemPayload.Line> lines = new ArrayList<>();
        for (Map.Entry<String, CartLine> line : cart.entrySet()) {
            if (entryById(line.getKey()) == null) {
                return;
            }
            // The quoted price, not the live one. The server rejects a
            // mismatch, which is the last guard for a change that landed
            // between this frame and the packet arriving.
            lines.add(new CashierRedeemPayload.Line(line.getKey(),
                    line.getValue().count(), line.getValue().quotedUnitPrice()));
        }
        PacketDistributor.sendToServer(new CashierRedeemPayload(lines));
        pendingSince = System.currentTimeMillis();
        balanceAtSend = menu.balance();
        playClick();
    }

    private boolean canBuy() {
        if (cart.isEmpty() || pendingSince != 0) {
            return false;
        }
        long total = cartTotal();
        return total >= 0 && total <= menu.spendable();
    }

    /** What the cart comes to at its quoted prices, or -1 if a line is gone. */
    private long cartTotal() {
        long total = 0;
        for (Map.Entry<String, CartLine> line : cart.entrySet()) {
            if (entryById(line.getKey()) == null) {
                return -1;
            }
            total += line.getValue().quotedUnitPrice() * (long) line.getValue().count();
        }
        return total;
    }

    private boolean hasStaleQuotes() {
        for (Map.Entry<String, CartLine> line : cart.entrySet()) {
            CashierCatalogPayload.Entry entry = entryById(line.getKey());
            if (entry != null && entry.buyback() != line.getValue().quotedUnitPrice()) {
                return true;
            }
        }
        return false;
    }

    private void clampCartScroll() {
        int maxScroll = Math.max(0, cart.size() - CART_ROWS);
        cartScroll = Math.clamp(cartScroll, 0, maxScroll);
    }

    /**
     * Finds an entry by its item id.
     * <p>
     * Against the raw list the server sent, not the sorted and filtered view.
     * A search that hides an item must not make the cart line for it
     * disappear along with it.
     */
    private @Nullable CashierCatalogPayload.Entry entryById(String itemId) {
        if (itemId.equals(NO_SELECTION)) {
            return null;
        }
        for (CashierCatalogPayload.Entry entry : ClientCashierState.entries()) {
            if (entry.itemId().equals(itemId)) {
                return entry;
            }
        }
        return null;
    }

    // --- The quantity field --------------------------------------------------------

    private void step(int direction) {
        if (quantity == null
                || (selected.equals(NO_SELECTION) && cartSelected.equals(NO_SELECTION))) {
            return;
        }
        int by = Screen.hasShiftDown() ? 10 : 1;
        applyQuantity(Math.clamp(quantityTyped() + (long) direction * by, 1, MAX_COUNT));
        playClick();
    }

    private void applyQuantity(int next) {
        setQuantitySilently(String.valueOf(next));
        if (!cartSelected.equals(NO_SELECTION)) {
            requote(cartSelected, next);
        }
    }

    /** Fills the field without the responder taking it for typing. */
    private void setQuantitySilently(String value) {
        if (quantity == null) {
            return;
        }
        suppressQuantity = true;
        quantity.setValue(value);
        suppressQuantity = false;
    }

    private int quantityTyped() {
        if (quantity == null) {
            return 0;
        }
        try {
            return Math.clamp(Long.parseLong(quantity.getValue()), 0, MAX_COUNT);
        } catch (NumberFormatException empty) {
            // An empty field, which the filter permits so a number can be
            // retyped from scratch.
            return 0;
        }
    }

    // --- Odds and ends ---------------------------------------------------------------

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** A price short enough for a grid cell. */
    private static String compact(long credits) {
        if (credits >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", credits / 1_000_000.0);
        }
        return format(credits);
    }

    /** The mod's own formatter, so every screen separates thousands the same way. */
    private static String format(long credits) {
        return CreditFormat.of(credits);
    }
}
