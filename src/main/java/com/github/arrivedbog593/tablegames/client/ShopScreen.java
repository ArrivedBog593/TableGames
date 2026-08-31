package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;
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
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The shop's screen: a grid of goods with prices.
 * <p>
 * Draws and sends button ids, nothing more. Prices shown here are for reading;
 * the server charges what its own table says.
 */
public class ShopScreen extends AbstractContainerScreen<ShopMenu> {


    private static final int COLUMNS = 3;
    private static final int ROWS = 4;
    /** Wide enough for a six-figure price beside the icon. */
    private static final int CELL_WIDTH = 67;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_X = 8;
    /** Below the controls' row, with a gap. The two used to touch. */
    private static final int GRID_Y = 35;

    /** The search field and the sort button share a row above the grid. */
    private static final int SEARCH_Y = 17;
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

    private int scroll;

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
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCatalog(graphics, mouseX, mouseY);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, 0x404040, false);
        // On the title row, right aligned. It used to sit at y=17, which is
        // exactly where the search field now lives — the field covered the
        // one number a shopper checks most.
        Component balance = Component.translatable(
                "tablegames.shop.balance", format(menu.balance()));
        graphics.drawString(font, balance,
                imageWidth - 8 - font.width(balance), titleLabelY, 0x2E7D32, false);
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
            boolean affordable = menu.balance() >= entry.price();

            // Each cell gets its own darker plate. The recess behind them is
            // one continuous well, so without this there is nothing saying
            // where one item's row ends and the next begins.
            graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                    hovered ? 0x50FFFFFF : 0x20000000);

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
        drawControl(graphics, x, y, SORT_W, Panels.OUTLINE, 0xFF555555, hovered);

        Component label = Component.literal(
                        (catalog.descending() ? "▼ " : "▲ "))
                .append(catalog.sortBy().label());
        graphics.drawString(font, label, x + (SORT_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);

        // The remember toggle. A "P" rather than a flag glyph: Minecraft's
        // font has no pin, and a missing glyph draws as nothing at all.
        int pinX = rememberButtonX();
        boolean pinHovered = isOver(mouseX, mouseY, pinX, y, REMEMBER_W, SEARCH_H);
        drawControl(graphics, pinX, y, REMEMBER_W, Panels.OUTLINE, 0xFF555555, pinHovered);
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
            for (CatalogView.SortBy option : CatalogView.SortBy.values()) {
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
            lines.add(Component.translatable("tablegames.shop.click_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
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
        if (search != null && search.isFocused()) {
            if (keyCode == InputConstants.KEY_ESCAPE) {
                search.setFocused(false);
                setFocused(null);
                return true;
            }
            // A letter typed into a search box is a letter, not the inventory
            // key. AbstractContainerScreen would otherwise close the shop on
            // "e" halfway through the word "netherite".
            if (search.keyPressed(keyCode, scanCode, modifiers) || search.canConsumeInput()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (search != null && isOver((int) mouseX, (int) mouseY,
                leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W, SEARCH_H)) {
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
        if (isOver((int) mouseX, (int) mouseY, rememberButtonX(), topPos + SEARCH_Y,
                REMEMBER_W, SEARCH_H)) {
            ScreenPreferences.setRememberSearch(!ScreenPreferences.rememberSearch());
            playClick();
            return true;
        }
        if (isOver((int) mouseX, (int) mouseY, sortButtonX(), topPos + SEARCH_Y,
                SORT_W, SEARCH_H)) {
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
        if (button == 0) {
            List<ShopCatalogPayload.Entry> entries = catalog.entries();
            int first = scroll * COLUMNS;
            for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
                int index = first + cell;
                if (index >= entries.size()) {
                    break;
                }
                int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
                int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;
                if (isOver((int) mouseX, (int) mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2)) {
                    ShopCatalogPayload.Entry entry = entries.get(index);
                    send(Screen.hasShiftDown()
                            // The catalog number the server sent, not the row
                            // this happens to be drawn in — the screen may be
                            // sorting or filtering locally.
                            ? ShopMenu.BUTTON_BUY_STACK + entry.number()
                            : ShopMenu.BUTTON_BUY_ONE + entry.number());
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        // The filtered size, not the whole catalog: a search that hides most
        // of the shop would otherwise still scroll past the end of it.
        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > ROWS) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, totalRows - ROWS);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    /** The vanilla button click, so these feel like the rest of the game. */
    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private void send(int buttonId) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
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