package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;
import com.github.arrivedbog593.tablegames.platform.network.ShopPurchasePayload;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The shop's screen: a grid of goods on the left, a cart on the right.
 * <p>
 * Buying used to be a single click on the grid, with shift for a stack. Two
 * arbitrary quantities, no confirmation, and a catalog that re-sorts itself
 * underneath the cursor — a misplaced click could spend a balance on
 * something the player was only reading about. Selecting, naming a quantity,
 * and confirming is slower by three clicks and correct by construction.
 * <p>
 * The cart holds entry numbers and quantities, never prices. Every figure
 * drawn here is looked up in the catalog the server last sent, so a price
 * that changes while the cart is open changes what the cart says it will
 * cost. That is also the price sent for the server to check against, which is
 * what makes the check meaningful: the player is only ever charged the number
 * they were looking at.
 */
public class ShopScreen extends AbstractContainerScreen<ShopMenu> {

    private static final int COLUMNS = 3;
    private static final int ROWS = 6;
    /** Wide enough for a six-figure price beside the icon. */
    private static final int CELL_WIDTH = 67;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_X = 8;
    /** Below the controls' row, with a gap. The two used to touch. */
    private static final int GRID_Y = 46;

    /**
     * The balance sits on a row of its own, between the panel title and the
     * search field.
     * <p>
     * It shared the title's row until the figure grew a second number, and
     * two right-aligned words ran straight through "Casino shop" on any
     * balance worth having. A row costs eleven pixels of a panel whose height
     * is a layout decision rather than a texture, which is cheap next to a
     * shopper reading a number that overlaps a word.
     */
    private static final int BALANCE_Y = 17;
    /** The search field and the sort button share a row above the grid. */
    private static final int SEARCH_Y = 28;
    /**
     * Tall enough for an accented capital.
     * <p>
     * A glyph is eight pixels and the accent on "Í" sits at the very top of
     * it, so a twelve-pixel button with the text two pixels down had the
     * accent poking through the bevel. Fourteen leaves three clear above and
     * three below.
     */
    private static final int SEARCH_H = 14;
    private static final int SEARCH_W = 118;
    private static final int SORT_W = 62;
    private static final int REMEMBER_W = 11;

    /** Where text sits inside a control: centered, then a pixel lower. */
    private static final int TEXT_Y = (SEARCH_H - 8) / 2 + 1;

    /**
     * The cart column, to the right of the grid.
     * <p>
     * Clear of the scroll arrows, which sit two pixels past the last cell.
     */
    private static final int CART_X = 222;
    private static final int CART_W = 110;
    /** The stepper row: minus the typed quantity, plus. */
    private static final int QTY_Y = GRID_Y;
    private static final int STEP_W = 16;
    /** The add button, directly under the stepper. */
    private static final int ADD_Y = QTY_Y + SEARCH_H + 4;
    /** The cart's own well. */
    private static final int CART_Y = ADD_Y + SEARCH_H + 6;
    private static final int CART_ROWS = 3;
    private static final int CART_ROW_H = 19;
    private static final int TOTAL_Y = CART_Y + CART_ROWS * CART_ROW_H + 4;
    private static final int BUY_Y = TOTAL_Y + 10;

    /** No entry picked. Zero is not a catalog number; those count from one. */
    private static final int NO_SELECTION = 0;

    /**
     * The palette, shared with the administration screens on purpose.
     * <p>
     * Green means it does the thing, brown means it undoes one, red means the
     * undoing is armed. An administrator and a player looking at two
     * different screens should not have to learn two vocabularies of color.
     */
    private static final int ACTION_FACE = 0xFF2E7D32;
    private static final int REMOVE_FACE = 0xFF6D4C41;
    private static final int ARMED_FACE = 0xFFB71C1C;
    private static final int DISABLED_FACE = 0xFF4A4A4A;

    /**
     * The buy button, while a price it was quoted, no longer matches the shop.
     * <p>
     * Amber rather than red: nothing has gone wrong, something has merely
     * changed and wants looking at before it is agreed to.
     */
    private static final int STALE_FACE = 0xFFB07B18;

    /** The neutral face for controls that only change the view. */
    private static final int CONTROL_FACE = 0xFF555555;

    /** The ring around a picked cell or cart line. */
    private static final int PICKED_RING = 0xFFE0B33A;

    /** Mirrors the server's own caps, so the screen refuses before the packet does. */
    private static final int MAX_LINES = 32;
    private static final int MAX_COUNT = 10_000;

    /**
     * How long the buy button stays disabled after a cart is sent.
     * <p>
     * Long enough to cover a round trip on a bad connection, short enough
     * that a dropped packet does not leave the button dead. Whichever comes
     * first: this, or the balance changing.
     */
    private static final long PENDING_MILLIS = 5_000;

    /**
     * How long the remove button stays armed after the first click.
     * <p>
     * The same two-step the administration screens use, for the same reason:
     * a click that undoes a line somebody spent a minute assembling should
     * take more than one.
     */
    private static final long CONFIRM_MILLIS = 3_000;

    private int scroll;
    private int cartScroll;

    /** The catalog number the player has picked, or {@link #NO_SELECTION}. */
    private int selected = NO_SELECTION;

    /**
     * The cart line the player has picked, or {@link #NO_SELECTION}.
     * <p>
     * Mutually exclusive with {@link #selected}, because the quantity field
     * has one subject at a time: either the amount about to be added, or the
     * amount already on a line. Two selections at once would leave the
     * stepper editing whichever the code happened to check first.
     */
    private int cartSelected = NO_SELECTION;

    /** When the remove button stops being armed. */
    private long confirmUntil;

    /**
     * When the buy button stops being armed.
     * <p>
     * Separate from the remove button, so arming one cannot leave the other
     * one press from spending a balance.
     */
    private long confirmBuyUntil;

    /**
     * Suppresses the quantity field's responder during a programmatic write.
     * <p>
     * Filling the field when a line is picked would otherwise be read as the
     * player typing, and write the value straight back into the cart it was
     * just read from.
     */
    private boolean suppressQuantity;

    /**
     * The cart: catalog number to quantity, in the order lines were added.
     * <p>
     * Numbers rather than entries, so that a price moving underneath is
     * reflected the next time the cart is drawn instead of being frozen at
     * whatever it was when the line was added. A cart quoting a price the
     * shop no longer charges is exactly the problem this whole change is
     * about.
     */
    private final Map<Integer, CartLine> cart = new LinkedHashMap<>();

    /**
     * One line of the cart: how many, and at what price it was put there.
     * <p>
     * The price is frozen deliberately. Looking it up live instead meant the
     * cart quietly agreed with every change the shop made, so a player who
     * had decided to spend two thousand could press buy a moment after a
     * reprice and spend five — and the server's check would find nothing
     * wrong, because the screen had already adopted the new figure before
     * sending it. Freezing the quote is what makes the disagreement visible,
     * and visible is the only thing that puts the decision back with the
     * player.
     *
     * @param count           how many lots
     * @param quotedUnitPrice what one cost when the line was added
     */
    private record CartLine(int count, long quotedUnitPrice) {
    }

    /**
     * When a cart was sent, and what the balance was at that moment.
     * <p>
     * There is no acknowledgement packet. The balance always falls on a
     * successful purchase — every entry costs at least one credit — so a
     * change in it is the signal that the cart went through and may be
     * cleared. A failure leaves the balance alone, and the cart survives for
     * the player to fix, which is the point: losing nine lines because one of
     * them changed price would be a worse outcome than the bug being fixed
     * here.
     */
    private long pendingSince;
    private long balanceAtSend;

    /**
     * The catalog as this player has chosen to look at it.
     * <p>
     * Purchases quote the number the server sent, so sorting and filtering
     * here cannot mis-buy anything however far a row has moved.
     */
    private final CatalogView<ShopCatalogPayload.Entry> catalog =
            new CatalogView<>(ShopCatalogPayload.Entry::stack,
                    ShopCatalogPayload.Entry::price);

    /** Typed name or item id. Empty means everything is shown. */
    private EditBox search;

    /** How many lots of the selected entry to add. */
    private EditBox quantity;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        // Taken from the menu, which is where the slots are positioned. The
        // two have to agree, and only one of them can own the number.
        this.imageWidth = ShopMenu.PANEL_WIDTH;
        this.imageHeight = ShopMenu.PANEL_HEIGHT;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();

        // On a resize, whatever is on screen wins; on a fresh opening, the
        // remembered query if the player left that on.
        String carried = search == null ? ScreenPreferences.shopSearch() : search.getValue();
        // Unbordered: drawControls paints the frame. Positioned so its text
        // lands on TEXT_Y, the same line the buttons use.
        search = new EditBox(font, leftPos + GRID_X + 4, topPos + SEARCH_Y + TEXT_Y,
                SEARCH_W - 8, 8, Component.translatable("tablegames.shop.search"));
        search.setBordered(false);
        search.setHint(Component.translatable("tablegames.shop.search"));
        search.setResponder(text -> {
            catalog.setQuery(text);
            ScreenPreferences.setShopSearch(text);
            scroll = 0;
        });
        // A resize rebuilds the widget, so what was typed has to be carried
        // over, or the list silently unfilters itself.
        search.setValue(carried);
        addRenderableWidget(search);

        String quantityCarried = quantity == null ? "1" : quantity.getValue();
        quantity = new EditBox(font, leftPos + CART_X + STEP_W + 6, topPos + QTY_Y + TEXT_Y,
                CART_W - 2 * STEP_W - 12, 8,
                Component.translatable("tablegames.cart.quantity"));
        quantity.setBordered(false);
        // Digits only, and bounded here rather than on submission. A field
        // that accepts "99999999" and then quietly refuses is worse than one
        // that never lets it be typed.
        quantity.setFilter(text -> text.isEmpty() || text.matches("\\d{1,5}"));
        // Typing edits the picked line as it is typed, so there is no third
        // button for applying a number the player can already see.
        quantity.setResponder(text -> {
            if (suppressQuantity || cartSelected == NO_SELECTION) {
                return;
            }
            int typed = quantityTyped();
            if (typed > 0) {
                requote(cartSelected, typed);
            }
        });
        quantity.setValue(quantityCarried);
        addRenderableWidget(quantity);

        catalog.restore(ScreenPreferences.shopSort(),
                ScreenPreferences.shopSortDescending());
    }

    /**
     * The frame, drawn rather than blitted.
     * <p>
     * The texture used to fix this panel at 176 wides, which is why a six-figure
     * price ran off its cell and why the row of controls had nowhere
     * to go. Drawn, the size is a layout decision instead of an image's.
     */
    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        Panels.panel(graphics, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        Panels.recess(graphics, leftPos + GRID_X - 1, topPos + GRID_Y - 1,
                COLUMNS * CELL_WIDTH + 2, ROWS * CELL_HEIGHT + 2);
        Panels.recess(graphics, leftPos + CART_X - 1, topPos + CART_Y - 1,
                CART_W + 2, CART_ROWS * CART_ROW_H + 2);

        int inventoryLeft = leftPos + (imageWidth - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                Panels.slot(graphics, inventoryLeft + column * 18,
                        topPos + ShopMenu.INVENTORY_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            Panels.slot(graphics, inventoryLeft + column * 18,
                    topPos + ShopMenu.HOTBAR_Y);
        }
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        settlePending();
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCatalog(graphics, mouseX, mouseY);
        renderCart(graphics, mouseX, mouseY);
        renderTooltip(graphics, mouseX, mouseY);
    }

    /**
     * Decides whether a sent cart has landed.
     * <p>
     * Run before anything is drawn, so the buy button and the cart list agree
     * within a single frame.
     */
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
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, 0x404040, false);

        // Two numbers when part of the balance is spoken for, and the
        // spendable one first: that is the figure the prices below are being
        // compared against, and the one that explains a grayed row.
        Component balance = menu.committed() > 0
                ? Component.translatable("tablegames.shop.balance_committed",
                format(menu.spendable()), format(menu.balance()))
                : Component.translatable("tablegames.shop.balance", format(menu.balance()));
        graphics.drawString(font, balance, GRID_X, BALANCE_Y, 0x2E7D32, false);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        // Refreshed here, before anything is drawn, so the grid, the tooltip
        // and the click handler all read the same list. Drawing from the raw
        // catalog while resolving clicks against the sorted one would put a
        // different item under the cursor than the one bought.
        catalog.accept(ClientShopState.entries());

        List<ShopCatalogPayload.Entry> entries = catalog.entries();
        if (entries.isEmpty()) {
            // A shop with nothing in it and a search that matched nothing look
            // identical on screen and are entirely different problems. Saying
            // "nothing for sale" to somebody who has just mistyped sends them
            // to ask an admin why the shop was emptied.
            // Centered in the well, with a shadow. It used to sit flush
            // against the top edge in a gray a shade off the well's own,
            // which made it read as part of the background.
            Component message = Component.translatable(catalog.isFiltered()
                    ? "tablegames.shop.no_matches"
                    : "tablegames.shop.empty");
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
            ShopCatalogPayload.Entry entry = entries.get(index);
            int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
            int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;

            boolean hovered = isOver(mouseX, mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2);
            boolean picked = entry.number() == selected;
            // Spendable, not balance: a wager already promised these credits
            // to a spin, and drawing the row as buyable would be a lie the
            // click then has to break.
            boolean affordable = menu.spendable() >= entry.price();

            // Each cell gets its own darker plate. The recess behind them is
            // one continuous well, so without this there is nothing saying
            // where one item's row ends and the next begins.
            graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                    hovered ? 0x50FFFFFF : 0x20000000);
            if (picked) {
                // An outline rather than a wash, so the selection is still
                // visible under the cursor's own highlight. The same ring the
                // administration screen draws, in the same gold.
                graphics.renderOutline(x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2, PICKED_RING);
            }

            // The real stack, so the enchantment glint, the damage bar and
            // the count all render the way they would in an inventory.
            graphics.renderItem(entry.stack(), x + 2, y + 2);
            graphics.renderItemDecorations(font, entry.stack(), x + 2, y + 2);

            // The cell now fits "100,000". Seven figures still would not, so
            // the abbreviation stays for those rather than growing the panel
            // again for a number a casino rarely charges.
            graphics.drawString(font, compact(entry.price()),
                    x + 21, y + 6, affordable ? 0xFFFFFF : 0xFF6B6B, false);
        }

        drawControls(graphics, mouseX, mouseY);

        int totalRows = (entries.size() + COLUMNS - 1) / COLUMNS;
        if (scroll > 0) {
            graphics.drawString(font, "▲",
                    leftPos + GRID_X + COLUMNS * CELL_WIDTH + 2, topPos + GRID_Y,
                    Panels.LABEL_TEXT, false);
        }
        if (scroll + ROWS < totalRows) {
            graphics.drawString(font, "▼",
                    leftPos + GRID_X + COLUMNS * CELL_WIDTH + 2,
                    // The bottom of the recess, not the top of the last row.
                    // The glyph is 8 tall, so it sits inside the well the same
                    // way the up arrow does at the other end.
                    topPos + GRID_Y + ROWS * CELL_HEIGHT - 9, Panels.LABEL_TEXT, false);
        }
    }

    /**
     * The whole controls row, in one place.
     * <p>
     * Both the populated and the empty catalog draw it, and having the three
     * pieces written out twice is how the search frame ended up on only one
     * of them. Order matters: the frames go down before the field's text, or
     * the text would be painted over.
     */
    private void drawControls(GuiGraphics graphics, int mouseX, int mouseY) {
        // Vanilla's own two-tone border: white while typing, gray otherwise.
        // Losing that cue was the cost of drawing the frame ourselves, and it
        // is the only thing that says where the keyboard is going.
        boolean typing = search != null && search.isFocused();
        drawControl(graphics, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);
        drawSortButton(graphics, mouseX, mouseY);
        if (search != null) {
            search.render(graphics, mouseX, mouseY, 0f);
        }
    }

    /**
     * The cart column: the stepper, the add button, the lines, and the till.
     * <p>
     * Drawn after the grid so its own frames sit on top of nothing, and
     * before the tooltip pass so a hovered cart line can still explain itself.
     */
    private void renderCart(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean editing = cartSelected != NO_SELECTION;
        boolean hasPick = editing || entryByNumber(selected) != null;

        // The stepper. Grayed as a set when nothing is picked, because a
        // quantity with no subject is a control that cannot do anything.
        int minusX = leftPos + CART_X;
        int plusX = leftPos + CART_X + CART_W - STEP_W;
        int qtyY = topPos + QTY_Y;
        boolean typingQuantity = quantity != null && quantity.isFocused();

        drawStep(graphics, minusX, qtyY, "-", hasPick,
                isOver(mouseX, mouseY, minusX, qtyY, STEP_W, SEARCH_H));
        drawControl(graphics, leftPos + CART_X + STEP_W + 2, qtyY,
                CART_W - 2 * STEP_W - 4,
                typingQuantity ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typingQuantity);
        drawStep(graphics, plusX, qtyY, "+", hasPick,
                isOver(mouseX, mouseY, plusX, qtyY, STEP_W, SEARCH_H));
        if (quantity != null) {
            quantity.render(graphics, mouseX, mouseY, 0f);
        }

        // One button doing two jobs, because the two are never available at
        // once: a line is either being built or being edited.
        int actionY = topPos + ADD_Y;
        boolean armed = isArmed();
        Component actionLabel = editing
                ? Component.translatable(armed
                ? "tablegames.cart.remove_confirm" : "tablegames.cart.remove")
                : Component.translatable("tablegames.cart.add");
        boolean canAct = editing
                || (hasPick && quantityTyped() > 0 && cart.size() < MAX_LINES);
        int actionFace = editing
                ? (armed ? ARMED_FACE : REMOVE_FACE)
                : ACTION_FACE;
        drawButton(graphics, leftPos + CART_X, actionY, actionLabel, canAct,
                isOver(mouseX, mouseY, leftPos + CART_X, actionY, CART_W, SEARCH_H),
                actionFace);

        // The lines themselves.
        if (cart.isEmpty()) {
            Component empty = Component.translatable("tablegames.cart.empty_hint");
            graphics.drawString(font, empty,
                    leftPos + CART_X + (CART_W - font.width(empty)) / 2,
                    topPos + CART_Y + 6, 0xFFE8E8E8, true);
        } else {
            List<Integer> numbers = new ArrayList<>(cart.keySet());
            for (int row = 0; row < CART_ROWS; row++) {
                int index = cartScroll + row;
                if (index >= numbers.size()) {
                    break;
                }
                int number = numbers.get(index);
                int y = topPos + CART_Y + row * CART_ROW_H;
                drawCartLine(graphics, mouseX, mouseY, number, cart.get(number), y);
            }
            if (cartScroll > 0) {
                graphics.drawString(font, "▲", leftPos + CART_X + CART_W - 7,
                        topPos + CART_Y + 1, Panels.LABEL_TEXT, false);
            }
            if (cartScroll + CART_ROWS < numbers.size()) {
                graphics.drawString(font, "▼", leftPos + CART_X + CART_W - 7,
                        topPos + CART_Y + CART_ROWS * CART_ROW_H - 9,
                        Panels.LABEL_TEXT, false);
            }
        }

        // The till. Dark on the panel's own light gray, like every other
        // label sitting directly on it — white was legible against the wells
        // above and all but invisible here.
        long total = cartTotal();
        Component totalLine = total < 0
                ? Component.translatable("tablegames.cart.unavailable")
                : Component.translatable("tablegames.cart.total", format(total));
        int totalColor = total < 0 || total > menu.spendable()
                ? 0xFF9C1C1C : Panels.LABEL_TEXT;
        graphics.drawString(font, totalLine, leftPos + CART_X, topPos + TOTAL_Y,
                totalColor, false);

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

    /** Whether the remove button is waiting for its second click. */
    private boolean isArmed() {
        return System.currentTimeMillis() < confirmUntil;
    }

    /** One line of the cart: what it is, how many, and what that comes to. */
    private void drawCartLine(GuiGraphics graphics, int mouseX, int mouseY,
                              int number, CartLine line, int y) {
        int x = leftPos + CART_X;
        boolean hovered = isOver(mouseX, mouseY, x, y, CART_W, CART_ROW_H - 1);
        boolean picked = number == cartSelected;
        graphics.fill(x, y, x + CART_W, y + CART_ROW_H - 1,
                hovered ? 0x50FFFFFF : 0x20000000);
        if (picked) {
            graphics.renderOutline(x, y, CART_W, CART_ROW_H - 1, PICKED_RING);
        }

        ShopCatalogPayload.Entry entry = entryByNumber(number);
        if (entry == null) {
            // The entry was removed while it sat in the cart. Drawn rather
            // than dropped, so the player can see why the till is refusing
            // instead of watching a line vanish on its own.
            graphics.drawString(font, Component.translatable("tablegames.cart.gone"),
                    x + 2, y + 6, 0xFFFF6B6B, false);
            return;
        }

        graphics.renderItem(entry.stack(), x + 1, y + 1);
        graphics.renderItemDecorations(font, entry.stack(), x + 1, y + 1);

        graphics.drawString(font, "x" + line.count(), x + 20, y + 6, 0xFFFFFFFF, false);

        // The quoted subtotal, and amber when the shop has moved underneath
        // it. Drawing the live figure instead would hide the very thing the
        // player needs to notice.
        boolean stale = entry.price() != line.quotedUnitPrice();
        String subtotal = compact(line.quotedUnitPrice() * (long) line.count());
        graphics.drawString(font, subtotal,
                x + CART_W - 9 - font.width(subtotal), y + 6,
                stale ? 0xFFE0B33A : 0xFFCFCFCF, false);
    }

    private int sortButtonX() {
        return leftPos + GRID_X + SEARCH_W + 4;
    }

    /** The outlined control every widget in the row shares. */
    private void drawControl(GuiGraphics graphics, int x, int y, int width,
                             int outline, int face, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + SEARCH_H + 1, outline);
        graphics.fill(x, y, x + width, y + SEARCH_H,
                hovered ? face + 0x00191919 : face);
    }

    /** A labeled button, grayed out when it would do nothing. */
    private void drawButton(GuiGraphics graphics, int x, int y,
                            Component label, boolean enabled, boolean hovered, int face) {
        drawControl(graphics, x, y, ShopScreen.CART_W, Panels.OUTLINE,
                enabled ? face : DISABLED_FACE, enabled && hovered);
        graphics.drawString(font, label, x + (ShopScreen.CART_W - font.width(label)) / 2,
                y + TEXT_Y, enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    /** One end of the stepper. */
    private void drawStep(GuiGraphics graphics, int x, int y, String glyph,
                          boolean enabled, boolean hovered) {
        drawControl(graphics, x, y, STEP_W, Panels.OUTLINE,
                enabled ? CONTROL_FACE : DISABLED_FACE, enabled && hovered);
        graphics.drawString(font, glyph, x + (STEP_W - font.width(glyph)) / 2,
                y + TEXT_Y, enabled ? 0xFFFFFFFF : 0xFF7A7A7A, false);
    }

    private int rememberButtonX() {
        return sortButtonX() + SORT_W + 3;
    }

    /**
     * One button for both the criterion and the direction: left click cycles
     * what to sort by, right click flips it. Two buttons would take room the
     * panel does not have, and the arrow says which way it is going.
     */
    private void drawSortButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = sortButtonX();
        int y = topPos + SEARCH_Y;
        boolean hovered = isOver(mouseX, mouseY, x, y, SORT_W, SEARCH_H);
        // A dark face rather than the panel's own gray. The surrounding frame
        // is light, so a light button on a light panel loses its edges; dark
        // reads as a control rather than as more background.
        drawControl(graphics, x, y, SORT_W, Panels.OUTLINE, CONTROL_FACE, hovered);

        Component label = Component.literal(
                        (catalog.descending() ? "▼ " : "▲ "))
                .append(catalog.sortBy().label());
        graphics.drawString(font, label, x + (SORT_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);

        // The remember toggle. A "P" rather than a flag glyph: Minecraft's
        // font has no pin, and a missing glyph draws as nothing at all.
        int pinX = rememberButtonX();
        boolean pinHovered = isOver(mouseX, mouseY, pinX, y, REMEMBER_W, SEARCH_H);
        drawControl(graphics, pinX, y, REMEMBER_W, Panels.OUTLINE, CONTROL_FACE, pinHovered);
        graphics.drawString(font, "P", pinX + (REMEMBER_W - font.width("P")) / 2,
                y + TEXT_Y,
                ScreenPreferences.rememberSearch() ? 0xFFE0B33A : 0xFF9A9A9A, false);
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        int controlsY = topPos + SEARCH_Y;
        if (isOver(mouseX, mouseY, rememberButtonX(), controlsY, REMEMBER_W, SEARCH_H)) {
            boolean on = ScreenPreferences.rememberSearch();
            graphics.renderComponentTooltip(font, List.of(
                            Component.translatable(on
                                    ? "tablegames.shop.remember_on"
                                    : "tablegames.shop.remember_off"),
                            Component.translatable(on
                                            ? "tablegames.shop.remember_on_hint"
                                            : "tablegames.shop.remember_off_hint")
                                    .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return;
        }
        if (isOver(mouseX, mouseY, sortButtonX(), controlsY, SORT_W, SEARCH_H)) {
            // The whole set, not only the one in use: the button cycles, so a
            // player deciding whether to press it wants to know what comes
            // next rather than what they already have.
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable("tablegames.shop.sort_title"));
            lines.add(Component.translatable("tablegames.shop.sort_hint")
                    .withStyle(ChatFormatting.GRAY));
            for (CatalogView.SortBy option : catalog.options()) {
                lines.add(Component.translatable("tablegames.shop.sort_option",
                                option.label(),
                                Component.translatable("tablegames.sort."
                                        + option.name().toLowerCase(Locale.ROOT)
                                        + ".describe"))
                        .withStyle(option == catalog.sortBy()
                                ? ChatFormatting.WHITE
                                : ChatFormatting.DARK_GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        if (renderCartTooltip(graphics, mouseX, mouseY)) {
            return;
        }

        List<ShopCatalogPayload.Entry> entries = catalog.entries();
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
            ShopCatalogPayload.Entry entry = entries.get(index);
            // The item's own tooltip first, so enchantments, custom names and
            // lore show exactly as they will once bought. Building a line from
            // the item id would have shown "Netherite Sword" for a stack that
            // is anything but.
            List<Component> lines = new ArrayList<>(
                    // Minecraft.getInstance() rather than the inherited field,
                    // which is declared nullable because it is unset between
                    // constructing a screen and init(). It cannot be null once
                    // a tooltip is being drawn, but taking the instance
                    // directly means not having to argue the point.
                    getTooltipFromItem(Minecraft.getInstance(), entry.stack()));
            lines.add(Component.translatable("tablegames.shop.unit_price",
                    format(entry.price())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tablegames.cart.select_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    /** @return true when the cursor was over something in the cart column */
    private boolean renderCartTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int actionY = topPos + ADD_Y;
        if (cartSelected != NO_SELECTION
                && isOver(mouseX, mouseY, leftPos + CART_X, actionY, CART_W, SEARCH_H)) {
            graphics.renderComponentTooltip(font, List.of(
                            Component.translatable("tablegames.cart.remove"),
                            Component.translatable("tablegames.cart.remove_hint")
                                    .withStyle(ChatFormatting.GRAY)),
                    mouseX, mouseY);
            return true;
        }

        int buyY = topPos + BUY_Y;
        if (isOver(mouseX, mouseY, leftPos + CART_X, buyY, CART_W, SEARCH_H)) {
            List<Component> lines = new ArrayList<>();
            if (hasStaleQuotes()) {
                graphics.renderComponentTooltip(font, List.of(
                                Component.translatable("tablegames.cart.accept_prices"),
                                Component.translatable("tablegames.cart.accept_prices_hint")
                                        .withStyle(ChatFormatting.GRAY)),
                        mouseX, mouseY);
                return true;
            }
            lines.add(Component.translatable("tablegames.cart.buy"));
            // Why it is grayed, rather than leaving the player to guess. A
            // disabled button with no explanation is the same as a broken one.
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

        List<Integer> numbers = new ArrayList<>(cart.keySet());
        for (int row = 0; row < CART_ROWS; row++) {
            int index = cartScroll + row;
            if (index >= numbers.size()) {
                break;
            }
            int y = topPos + CART_Y + row * CART_ROW_H;
            if (!isOver(mouseX, mouseY, leftPos + CART_X, y, CART_W, CART_ROW_H - 1)) {
                continue;
            }
            ShopCatalogPayload.Entry entry = entryByNumber(numbers.get(index));
            List<Component> lines = new ArrayList<>();
            if (entry == null) {
                lines.add(Component.translatable("tablegames.cart.gone")
                        .withStyle(ChatFormatting.RED));
            } else {
                lines.addAll(getTooltipFromItem(Minecraft.getInstance(), entry.stack()));
                CartLine line = cart.get(numbers.get(index));
                if (line != null && entry.price() != line.quotedUnitPrice()) {
                    lines.add(Component.translatable("tablegames.cart.quote_changed",
                                    format(line.quotedUnitPrice()), format(entry.price()))
                            .withStyle(ChatFormatting.GOLD));
                } else {
                    lines.add(Component.translatable("tablegames.shop.unit_price",
                            format(entry.price())).withStyle(ChatFormatting.GRAY));
                }
            }
            lines.add(Component.translatable("tablegames.cart.edit_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return true;
        }
        return false;
    }

    /**
     * Keeps typing from reaching the screen's own shortcuts.
     * <p>
     * Escape unfocuses the field rather than closing the shop, so the second
     * press closes it — the same two-step every text field in the game uses.
     * Without it a player who mistyped had to close the whole screen to
     * escape their own search box.
     */
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
            // Enter in the quantity field adds the line, which is what
            // somebody who just typed a number is reaching for. Not while a
            // line is picked: there the number is already applied as it is
            // typed, and Enter would add a second line for the same entry.
            if (field == quantity
                    && cartSelected == NO_SELECTION
                    && (keyCode == InputConstants.KEY_RETURN
                    || keyCode == InputConstants.KEY_NUMPADENTER)) {
                addToCart();
                return true;
            }
            // A letter typed into a search box is a letter, not the inventory
            // key. AbstractContainerScreen would otherwise close the shop on
            // "e" halfway through the word "netherite".
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
                // Right-click clears the same "right modifies rather than
                // executes" gesture the sort button uses.
                search.setValue("");
                return true;
            }
            setFocused(search);
            search.setFocused(true);
            return true;
        }
        if (isOver(x, y, rememberButtonX(), topPos + SEARCH_Y, REMEMBER_W, SEARCH_H)) {
            ScreenPreferences.setRememberSearch(!ScreenPreferences.rememberSearch());
            playClick();
            return true;
        }
        if (isOver(x, y, sortButtonX(), topPos + SEARCH_Y, SORT_W, SEARCH_H)) {
            if (button == 1) {
                catalog.toggleDirection();
            } else {
                catalog.cycleSort();
            }
            ScreenPreferences.setShopSort(catalog.sortBy(), catalog.descending());
            playClick();
            scroll = 0;
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

    /** Selecting an entry. Clicking the picked one again lets it go. */
    private boolean clickedCatalog(int mouseX, int mouseY) {
        List<ShopCatalogPayload.Entry> entries = catalog.entries();
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
            // The catalog number the server sent, not the row this happens to
            // be drawn in — the screen may be sorting or filtering locally.
            int number = entries.get(index).number();
            selected = number == selected ? NO_SELECTION : number;
            // One subject at a time. Leaving a line picked would have the
            // stepper editing the cart while the grid says it is adding.
            cartSelected = NO_SELECTION;
            confirmUntil = 0;
            setQuantitySilently("1");
            playClick();
            return true;
        }
        return false;
    }

    /** Everything in the right-hand column. */
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
            if (cartSelected == NO_SELECTION) {
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

        List<Integer> numbers = new ArrayList<>(cart.keySet());
        for (int row = 0; row < CART_ROWS; row++) {
            int index = cartScroll + row;
            if (index >= numbers.size()) {
                break;
            }
            int y = topPos + CART_Y + row * CART_ROW_H;
            if (!isOver(mouseX, mouseY, leftPos + CART_X, y, CART_W, CART_ROW_H - 1)) {
                continue;
            }
            pickCartLine(numbers.get(index));
            return true;
        }
        return false;
    }

    /**
     * Picks a cart line, or lets go of the one already picked.
     * <p>
     * The quantity field takes the line's own count, so the stepper carries
     * on from where the line is rather than from one.
     */
    private void pickCartLine(int number) {
        confirmUntil = 0;
        if (cartSelected == number) {
            cartSelected = NO_SELECTION;
        } else {
            cartSelected = number;
            selected = NO_SELECTION;
            CartLine line = cart.get(number);
            setQuantitySilently(String.valueOf(line == null ? 1 : line.count()));
        }
        playClick();
    }

    /**
     * Takes the picked line out, on the second click.
     * <p>
     * Two clicks because a cart is assembled by hand and a stray one on a
     * button that shares its place with "add" would undo that work with no
     * way back.
     */
    private void removePicked() {
        if (cartSelected == NO_SELECTION) {
            return;
        }
        if (!isArmed()) {
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

    /**
     * Keeps the cart's scroll inside the list.
     * <p>
     * The bound is computed before it is used. Written as one expression, it
     * went negative on an emptied cart and threw out of the click handler,
     * which is a crash for a mistake worth nothing.
     */
    private void clampCartScroll() {
        int maxScroll = Math.max(0, cart.size() - CART_ROWS);
        cartScroll = Math.clamp(cartScroll, 0, maxScroll);
    }

    /** Nudges the typed quantity. Shift moves it by ten, as elsewhere in the game. */
    private void step(int direction) {
        if (quantity == null
                || (selected == NO_SELECTION && cartSelected == NO_SELECTION)) {
            return;
        }
        int by = Screen.hasShiftDown() ? 10 : 1;
        applyQuantity(Math.clamp(quantityTyped() + (long) direction * by,
                1, MAX_COUNT));
        playClick();
    }

    /** Writes the field and the picked line with it. */
    private void applyQuantity(int next) {
        setQuantitySilently(String.valueOf(next));
        if (cartSelected != NO_SELECTION) {
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

    /**
     * Puts the selected entry in the cart.
     * <p>
     * Adding the same entry twice adds up rather than making a second line. A
     * cart with two lines for one item is one the player has to read twice to
     * know what they are buying, and one the server has to reject.
     */
    private void addToCart() {
        int count = quantityTyped();
        // Resolved once and kept. Calling this twice and only checking the
        // first result left the second free to be null, which is a real
        // window, however narrow: the catalog can arrive between the two.
        ShopCatalogPayload.Entry entry = entryByNumber(selected);
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
        // Quoted at the price now on the grid, because that is the figure the
        // player is looking at as they press add.
        cart.put(selected, new CartLine(total, entry.price()));
        // A cart that changed is a cart the player has not agreed to yet.
        confirmBuyUntil = 0;
        playClick();
    }

    /**
     * Sends the cart, on the second press.
     * <p>
     * Buying is the one thing on this screen that cannot be undone, and the
     * button sits directly under a list that changes as the shop is edited.
     * A press that arms and a press that spends is a small tax on every
     * purchase and the difference between a stray click and a lost balance.
     */
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
        List<ShopPurchasePayload.Line> lines = new ArrayList<>();
        for (Map.Entry<Integer, CartLine> line : cart.entrySet()) {
            if (entryByNumber(line.getKey()) == null) {
                return;
            }
            // The quoted price, not the live one. The server rejects a
            // mismatch, which is the last guard for a change that landed
            // between this frame and the packet arriving.
            lines.add(new ShopPurchasePayload.Line(line.getKey(),
                    line.getValue().count(), line.getValue().quotedUnitPrice()));
        }
        PacketDistributor.sendToServer(new ShopPurchasePayload(lines));
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

    /**
     * What the cart comes to at the prices it was quoted, or -1 if a line no
     * longer resolves.
     */
    private long cartTotal() {
        long total = 0;
        for (Map.Entry<Integer, CartLine> line : cart.entrySet()) {
            if (entryByNumber(line.getKey()) == null) {
                return -1;
            }
            total += line.getValue().quotedUnitPrice() * (long) line.getValue().count();
        }
        return total;
    }

    /** Whether the shop has moved a price out from under any line. */
    private boolean hasStaleQuotes() {
        for (Map.Entry<Integer, CartLine> line : cart.entrySet()) {
            if (isStale(line.getKey(), line.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean isStale(int number, CartLine line) {
        ShopCatalogPayload.Entry entry = entryByNumber(number);
        return entry != null && entry.price() != line.quotedUnitPrice();
    }

    /** Changes a line's count without disturbing the price it was quoted. */
    private void requote(int number, int count) {
        CartLine line = cart.get(number);
        if (line != null) {
            cart.put(number, new CartLine(count, line.quotedUnitPrice()));
            // A cart that changed is a cart the player has not agreed to yet.
            confirmBuyUntil = 0;
        }
    }

    /**
     * Accepts the shop's current prices for every line.
     * <p>
     * The only way past a stale cart and deliberately a separate press from
     * buying: seeing the new number and agreeing to it are the two halves of
     * a decision that used to happen silently.
     */
    private void acceptNewPrices() {
        for (Map.Entry<Integer, CartLine> line : new ArrayList<>(cart.entrySet())) {
            ShopCatalogPayload.Entry entry = entryByNumber(line.getKey());
            if (entry != null) {
                cart.put(line.getKey(), new CartLine(line.getValue().count(), entry.price()));
            }
        }
        confirmBuyUntil = 0;
        playClick();
    }

    /**
     * Finds an entry by its catalog number.
     * <p>
     * Against the raw list the server sent, not the sorted and filtered view.
     * A search that hides an item must not make the cart line for it
     * disappear along with it.
     */
    private @Nullable ShopCatalogPayload.Entry entryByNumber(int number) {
        if (number == NO_SELECTION) {
            return null;
        }
        for (ShopCatalogPayload.Entry entry : ClientShopState.entries()) {
            if (entry.number() == number) {
                return entry;
            }
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (isOver((int) mouseX, (int) mouseY, leftPos + CART_X, topPos + CART_Y,
                CART_W, CART_ROWS * CART_ROW_H)) {
            if (cart.size() > CART_ROWS) {
                cartScroll -= (int) Math.signum(deltaY);
                clampCartScroll();
            }
            return true;
        }
        // The filtered size, not the whole catalog: a search that hides most
        // of the shop would otherwise still scroll past the end of it.
        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
        int maxScroll = Math.max(0, totalRows - ROWS);
        scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, maxScroll);
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    /** The vanilla button click, so these feel like the rest of the game. */
    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /**
     * A price short enough for a grid cell: 4,000 stays, 100,000 becomes
     * "100k", ten million becomes "10.0M".
     * <p>
     * Only for the grid. Everywhere with room shows the real figure, because
     * an abbreviation is a convenience and not a number anybody should have
     * to do arithmetic with.
     */
    private static String compact(long credits) {
        if (credits >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", credits / 1_000_000.0);
        }
        return format(credits);
    }

    private static String format(long credits) {
        return CreditFormat.of(credits);
    }
}
