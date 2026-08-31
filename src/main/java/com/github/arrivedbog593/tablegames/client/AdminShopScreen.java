package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.menu.AdminShopMenu;
import com.github.arrivedbog593.tablegames.platform.network.AdminShopActionPayload;
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
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuring what the shop sells.
 * <p>
 * The same grid the players see, plus a slot and a price field. Deliberately
 * the same: an admin arranging a shop should be looking at what a customer
 * will, not at a different rendering of the same list.
 * <p>
 * One button rather than three. With nothing selected it lists whatever is in
 * the slot; with an entry selected it reprices that entry. Removing is a
 * shift click on the entry itself, where the thing being removed is under the
 * cursor — a Remove button and a selection are two places to look for the
 * answer to "which one".
 */
public class AdminShopScreen extends AbstractContainerScreen<AdminShopMenu> {

    private static final int COLUMNS = 3;
    private static final int ROWS = 4;
    private static final int CELL_WIDTH = 67;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 35;

    private static final int SEARCH_Y = 17;
    private static final int SEARCH_H = 14;
    private static final int SEARCH_W = 118;
    private static final int SORT_W = 62;
    private static final int REMEMBER_W = 11;
    private static final int TEXT_INSET = 4;
    private static final int TEXT_Y = (SEARCH_H - 8) / 2 + 1;

    /** The price field and the action button, beside the listing slot. */
    private static final int ACTION_Y = AdminShopMenu.INPUT_Y + 2;
    private static final int PRICE_X = 32;
    private static final int PRICE_W = 90;
    private static final int ACTION_X = 128;
    private static final int ACTION_W = 84;

    /** Nothing selected. The button lists whatever is in the slot. */
    private static final int NO_SELECTION = 0;

    private int scroll;

    /** The catalog number being edited, or {@link #NO_SELECTION}. */
    private int selected = NO_SELECTION;

    private final CatalogView<ShopCatalogPayload.Entry> catalog =
            new CatalogView<>(ShopCatalogPayload.Entry::stack,
                    ShopCatalogPayload.Entry::price);

    private EditBox search;
    private EditBox price;

    public AdminShopScreen(AdminShopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = AdminShopMenu.PANEL_WIDTH;
        this.imageHeight = AdminShopMenu.PANEL_HEIGHT;
        this.inventoryLabelY = AdminShopMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();

        String carriedSearch = search == null ? "" : search.getValue();
        search = new EditBox(font, leftPos + GRID_X + TEXT_INSET,
                topPos + SEARCH_Y + TEXT_Y, SEARCH_W - TEXT_INSET * 2, 8,
                Component.translatable("tablegames.shop.search"));
        search.setBordered(false);
        search.setHint(Component.translatable("tablegames.shop.search"));
        search.setResponder(text -> {
            catalog.setQuery(text);
            scroll = 0;
        });
        search.setValue(carriedSearch);
        addRenderableWidget(search);

        String carriedPrice = price == null ? "" : price.getValue();
        price = new EditBox(font, leftPos + PRICE_X + TEXT_INSET,
                topPos + ACTION_Y + TEXT_Y, PRICE_W - TEXT_INSET * 2, 8,
                Component.translatable("tablegames.admin.shop.price"));
        price.setBordered(false);
        price.setHint(Component.translatable("tablegames.admin.shop.price"));
        price.setMaxLength(12);
        price.setFilter(text -> text.chars().allMatch(Character::isDigit));
        price.setValue(carriedPrice);
        addRenderableWidget(price);

        catalog.restore(ScreenPreferences.shopSort(),
                ScreenPreferences.shopSortDescending());
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        Panels.panel(graphics, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        Panels.recess(graphics, leftPos + GRID_X - 1, topPos + GRID_Y - 1,
                COLUMNS * CELL_WIDTH + 2, ROWS * CELL_HEIGHT + 2);
        Panels.slot(graphics, leftPos + AdminShopMenu.INPUT_X, topPos + AdminShopMenu.INPUT_Y);

        int inventoryLeft = leftPos + (imageWidth - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                Panels.slot(graphics, inventoryLeft + column * 18,
                        topPos + AdminShopMenu.INVENTORY_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            Panels.slot(graphics, inventoryLeft + column * 18,
                    topPos + AdminShopMenu.HOTBAR_Y);
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
        graphics.drawString(font, title, titleLabelX, titleLabelY, Panels.LABEL_TEXT, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, Panels.LABEL_TEXT, false);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        catalog.accept(ClientShopState.entries());
        List<ShopCatalogPayload.Entry> entries = catalog.entries();

        if (entries.isEmpty()) {
            Component message = Component.translatable(catalog.isFiltered()
                    ? "tablegames.shop.no_matches"
                    : "tablegames.shop.empty");
            graphics.drawString(font, message,
                    leftPos + GRID_X + (COLUMNS * CELL_WIDTH - font.width(message)) / 2,
                    topPos + GRID_Y + 10, 0xFFE8E8E8, true);
        } else {
            int first = scroll * COLUMNS;
            for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
                int index = first + cell;
                if (index >= entries.size()) {
                    break;
                }
                ShopCatalogPayload.Entry entry = entries.get(index);
                int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
                int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;

                boolean hovered = isOver(mouseX, mouseY, x, y,
                        CELL_WIDTH - 2, CELL_HEIGHT - 2);
                boolean chosen = entry.number() == selected;

                graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                        hovered ? 0x50FFFFFF : 0x20000000);
                if (chosen) {
                    // An outline rather than a wash, so the selection is still
                    // visible under the cursor's own highlight.
                    graphics.renderOutline(x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2, 0xFFE0B33A);
                }

                graphics.renderItem(entry.stack(), x + 2, y + 2);
                graphics.renderItemDecorations(font, entry.stack(), x + 2, y + 2);
                // The number small and above, the price below. Both on one
                // line ran past the cell as soon as a price hit five figures,
                // and the number is what an admin scans for.
                graphics.drawString(font, "#" + entry.number(),
                        x + 21, y + 2, 0xFFB0B0B0, false);
                graphics.drawString(font, compact(entry.price()),
                        x + 21, y + 11, 0xFFFFFFFF, false);
            }
        }

        drawControls(graphics, mouseX, mouseY);
        drawActionRow(graphics, mouseX, mouseY);

        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
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

    private void drawControls(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean typing = search != null && search.isFocused();
        drawControl(graphics, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);

        int sortX = sortButtonX();
        boolean sortHovered = isOver(mouseX, mouseY, sortX, topPos + SEARCH_Y,
                SORT_W, SEARCH_H);
        drawControl(graphics, sortX, topPos + SEARCH_Y, SORT_W,
                Panels.OUTLINE, 0xFF555555, sortHovered);
        Component sortLabel = Component.literal(catalog.descending() ? "▼ " : "▲ ")
                .append(catalog.sortBy().label());
        graphics.drawString(font, sortLabel,
                sortX + (SORT_W - font.width(sortLabel)) / 2,
                topPos + SEARCH_Y + TEXT_Y, 0xFFFFFFFF, false);

        int pinX = rememberButtonX();
        boolean pinHovered = isOver(mouseX, mouseY, pinX, topPos + SEARCH_Y,
                REMEMBER_W, SEARCH_H);
        drawControl(graphics, pinX, topPos + SEARCH_Y, REMEMBER_W,
                Panels.OUTLINE, 0xFF555555, pinHovered);
        graphics.drawString(font, "P", pinX + (REMEMBER_W - font.width("P")) / 2,
                topPos + SEARCH_Y + TEXT_Y,
                ScreenPreferences.rememberSearch() ? 0xFFE0B33A : 0xFF9A9A9A, false);

        if (search != null) {
            search.render(graphics, mouseX, mouseY, 0f);
        }
    }

    private void drawActionRow(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean typing = price != null && price.isFocused();
        drawControl(graphics, leftPos + PRICE_X, topPos + ACTION_Y, PRICE_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);

        int x = leftPos + ACTION_X;
        int y = topPos + ACTION_Y;
        boolean hovered = isOver(mouseX, mouseY, x, y, ACTION_W, SEARCH_H);
        boolean ready = canAct();
        drawControl(graphics, x, y, ACTION_W, Panels.OUTLINE,
                ready ? 0xFF2E7D32 : 0xFF4A4A4A, hovered && ready);

        Component label = Component.translatable(selected == NO_SELECTION
                ? "tablegames.admin.shop.add"
                : "tablegames.admin.shop.set_price");
        graphics.drawString(font, label, x + (ACTION_W - font.width(label)) / 2,
                y + TEXT_Y, ready ? 0xFFFFFFFF : 0xFF9A9A9A, false);

        if (price != null) {
            price.render(graphics, mouseX, mouseY, 0f);
        }
    }

    /** Whether the action button would do anything if pressed. */
    private boolean canAct() {
        if (typedPrice() < 1) {
            return false;
        }
        return selected != NO_SELECTION || !menu.held().isEmpty();
    }

    private long typedPrice() {
        if (price == null || price.getValue().isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(price.getValue());
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }

    private void drawControl(GuiGraphics graphics, int x, int y, int width,
                             int outline, int face, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + SEARCH_H + 1, outline);
        graphics.fill(x, y, x + width, y + SEARCH_H, hovered ? face + 0x00191919 : face);
    }

    private int sortButtonX() {
        return leftPos + GRID_X + SEARCH_W + 4;
    }

    private int rememberButtonX() {
        return sortButtonX() + SORT_W + 3;
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        if (isOver(mouseX, mouseY, leftPos + ACTION_X, topPos + ACTION_Y,
                ACTION_W, SEARCH_H)) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(selected == NO_SELECTION
                    ? "tablegames.admin.shop.add"
                    : "tablegames.admin.shop.set_price"));
            lines.add(Component.translatable(selected == NO_SELECTION
                            ? "tablegames.admin.shop.add_hint"
                            : "tablegames.admin.shop.set_price_hint")
                    .withStyle(ChatFormatting.GRAY));
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
            List<Component> lines = new ArrayList<>(
                    getTooltipFromItem(Minecraft.getInstance(), entry.stack()));
            lines.add(Component.translatable("tablegames.admin.shop.entry_number",
                    entry.number(), format(entry.price())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tablegames.admin.shop.entry_hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        for (EditBox field : new EditBox[]{search, price}) {
            if (field != null && field.isFocused()) {
                if (keyCode == InputConstants.KEY_ESCAPE) {
                    field.setFocused(false);
                    setFocused(null);
                    return true;
                }
                if (field.keyPressed(keyCode, scanCode, modifiers)
                        || field.canConsumeInput()) {
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (focusField(search, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W, mx, my, button)
                || focusField(price, leftPos + PRICE_X, topPos + ACTION_Y,
                        PRICE_W, mx, my, button)) {
            return true;
        }

        if (isOver(mx, my, sortButtonX(), topPos + SEARCH_Y, SORT_W, SEARCH_H)) {
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
        if (isOver(mx, my, rememberButtonX(), topPos + SEARCH_Y, REMEMBER_W, SEARCH_H)) {
            ScreenPreferences.setRememberSearch(!ScreenPreferences.rememberSearch());
            playClick();
            return true;
        }
        if (isOver(mx, my, leftPos + ACTION_X, topPos + ACTION_Y, ACTION_W, SEARCH_H)) {
            act();
            return true;
        }

        if (button == 0 && clickedEntry(mx, my)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean focusField(EditBox field, int x, int y, int width,
                               int mouseX, int mouseY, int button) {
        if (field == null || !isOver(mouseX, mouseY, x, y, width, SEARCH_H)) {
            return false;
        }
        if (button == 1) {
            field.setValue("");
            return true;
        }
        setFocused(field);
        field.setFocused(true);
        return true;
    }

    /**
     * Selects an entry or removes it when shift is held.
     * <p>
     * Removing where the thing is, rather than through a button and a
     * selection: two places to look for the answer to "which one" is one place
     * too many when the answer is destructive.
     */
    private boolean clickedEntry(int mouseX, int mouseY) {
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
            if (Screen.hasShiftDown()) {
                PacketDistributor.sendToServer(AdminShopActionPayload.remove(entry.number()));
                // Numbers shift when something is removed, so a selection
                // made before this click no longer means what it did.
                selected = NO_SELECTION;
            } else {
                selected = entry.number() == selected ? NO_SELECTION : entry.number();
                price.setValue(selected == NO_SELECTION ? "" : String.valueOf(entry.price()));
            }
            playClick();
            return true;
        }
        return false;
    }

    private void act() {
        if (!canAct()) {
            return;
        }
        PacketDistributor.sendToServer(selected == NO_SELECTION
                ? AdminShopActionPayload.add(typedPrice())
                : AdminShopActionPayload.reprice(selected, typedPrice()));
        selected = NO_SELECTION;
        price.setValue("");
        playClick();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > ROWS) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, totalRows - ROWS);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private static String format(long credits) {
        return com.github.arrivedbog593.tablegames.platform.economy.CreditFormat.of(credits);
    }

    /** Short enough for a grid cell; the tooltip carries the exact figure. */
    private static String compact(long credits) {
        if (credits >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.1fM", credits / 1_000_000.0);
        }
        return format(credits);
    }
}
