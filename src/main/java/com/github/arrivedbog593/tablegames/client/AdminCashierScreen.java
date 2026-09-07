package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.menu.AdminCashierMenu;
import com.github.arrivedbog593.tablegames.platform.network.AdminCashierActionPayload;
import com.github.arrivedbog593.tablegames.platform.network.AdminCashierBatchPayload;
import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Configuring what the cashier pays for, without commands.
 * <p>
 * The shape of {@link AdminShopScreen}, which is deliberate: an administrator
 * who has learned one of these two screens has learned the other. Select an
 * entry, and the button above changes from pricing what is in the slot to
 * changing that entry's value; a remove button appears beneath it and asks for
 * a second click before it does anything.
 * <p>
 * The one thing this has that the shop does not is the buyback surcharge along
 * the bottom, which is the cashier's own setting rather than the casino's.
 * <p>
 * Entries priced by a datapack are marked and cannot be removed here, the same
 * way the command refuses. Their value can still be changed, because that
 * writes an override the datapack does not overwrite.
 */
public class AdminCashierScreen extends AbstractContainerScreen<AdminCashierMenu> {

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
    private static final int TEXT_INSET = 4;
    private static final int TEXT_Y = (SEARCH_H - 8) / 2 + 1;

    private static final int ACTION_Y = AdminCashierMenu.INPUT_Y + 2;
    private static final int VALUE_X = 32;
    private static final int VALUE_W = 90;
    private static final int ACTION_X = 128;
    private static final int ACTION_W = 84;
    private static final int REMOVE_Y = ACTION_Y + 18;

    /** The apply button, left of the remove button on the same row. */
    private static final int APPLY_X = GRID_X;
    private static final int APPLY_W = 114;

    /** The surcharge row, under the remove button. */
    private static final int SPREAD_Y = REMOVE_Y + 18;
    private static final int SPREAD_X = 126;
    private static final int SPREAD_W = 30;
    private static final int SPREAD_BUTTON_X = 160;
    private static final int SPREAD_BUTTON_W = 52;

    /** How long the remove button stays armed after the first click. */
    private static final long CONFIRM_MILLIS = 3_000;

    /** Nothing selected. The button prices whatever is in the slot. */
    private static final String NO_SELECTION = "";

    private final CatalogView<CashierCatalogPayload.Entry> catalog = new CatalogView<>(
            entry -> ItemIds.item(entry.itemId()).map(ItemStack::new).orElse(ItemStack.EMPTY),
            CashierCatalogPayload.Entry::value);

    private EditBox search;
    private EditBox value;
    private EditBox spread;

    private int scroll;

    /** The item id being edited, or {@link #NO_SELECTION}. */
    private String selected = NO_SELECTION;

    /** The catalog this screen last drew, compared by identity. */
    private List<CashierCatalogPayload.Entry> lastSeen = List.of();

    /** When the armed remove button goes back to being harmless. */
    private long confirmUntil;

    /**
     * Values typed but not yet sent, by item id.
     * <p>
     * Staged rather than applied one by one because recipes tie prices
     * together: nine diamonds make a block, so the pair can only move to a
     * new scale if both sides move in the same breath. Sent alone, each half
     * is correctly refused for opening a loop against the other.
     * <p>
     * Kept when the server refuses the set, so a wrong figure can be fixed
     * without retyping the rest.
     */
    private final Map<String, Long> pending = new LinkedHashMap<>();

    public AdminCashierScreen(AdminCashierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = AdminCashierMenu.PANEL_WIDTH;
        this.imageHeight = AdminCashierMenu.PANEL_HEIGHT;
        this.inventoryLabelY = AdminCashierMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();

        search = new EditBox(font, leftPos + GRID_X + TEXT_INSET, topPos + SEARCH_Y + TEXT_Y,
                SEARCH_W - TEXT_INSET * 2, 8, Component.translatable("tablegames.shop.search"));
        style(search, 32);
        search.setResponder(text -> {
            catalog.setQuery(text);
            scroll = 0;
        });
        addWidget(search);

        value = new EditBox(font, leftPos + VALUE_X + TEXT_INSET, topPos + ACTION_Y + TEXT_Y,
                VALUE_W - TEXT_INSET * 2, 8, Component.literal(""));
        style(value, 12);
        value.setFilter(AdminCashierScreen::isNumber);
        addWidget(value);

        spread = new EditBox(font, leftPos + SPREAD_X + TEXT_INSET, topPos + SPREAD_Y + TEXT_Y,
                SPREAD_W - TEXT_INSET * 2, 8, Component.literal(""));
        style(spread, 3);
        spread.setFilter(AdminCashierScreen::isNumber);
        spread.setValue(String.valueOf(menu.spreadPercent()));
        addWidget(spread);
    }

    private void style(EditBox box, int maxLength) {
        box.setMaxLength(maxLength);
        box.setBordered(false);
        box.setTextColor(0xFFFFFF);
    }

    private static boolean isNumber(String text) {
        return text.chars().allMatch(Character::isDigit);
    }

    @Override
    protected void renderBg(@NotNull GuiGraphics graphics, float partialTick,
                            int mouseX, int mouseY) {
        Panels.panel(graphics, leftPos, topPos, leftPos + imageWidth, topPos + imageHeight);
        Panels.recess(graphics, leftPos + GRID_X - 1, topPos + GRID_Y - 1,
                COLUMNS * CELL_WIDTH + 2, ROWS * CELL_HEIGHT + 2);

        int inventoryLeft = leftPos + (imageWidth - 9 * 18) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                Panels.slot(graphics, inventoryLeft + column * 18,
                        topPos + AdminCashierMenu.INVENTORY_Y + row * 18);
            }
        }
        for (int column = 0; column < 9; column++) {
            Panels.slot(graphics, inventoryLeft + column * 18,
                    topPos + AdminCashierMenu.HOTBAR_Y);
        }
        Panels.slot(graphics, leftPos + AdminCashierMenu.INPUT_X,
                topPos + AdminCashierMenu.INPUT_Y);
    }

    @Override
    protected void renderLabels(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, Panels.LABEL_TEXT, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, Panels.LABEL_TEXT, false);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCatalog(graphics, mouseX, mouseY);
        renderControls(graphics, mouseX, mouseY);
        renderTooltip(graphics, mouseX, mouseY);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        List<CashierCatalogPayload.Entry> incoming = ClientCashierState.entries();
        if (incoming != lastSeen) {
            lastSeen = incoming;
            // Somebody else edited the table. A selection is an item id, and
            // ids do not move, but the entry behind it may be gone, and an
            // armed remove button must not survive the change.
            confirmUntil = 0;
            if (!selected.equals(NO_SELECTION) && incoming.stream()
                    .noneMatch(entry -> entry.itemId().equals(selected))) {
                selected = NO_SELECTION;
                value.setValue("");
            }
            spread.setValue(String.valueOf(menu.spreadPercent()));
            // Anything the table now agrees with has been applied. What is
            // left was refused, or belongs to a set still being built.
            pending.entrySet().removeIf(staged -> incoming.stream().anyMatch(entry ->
                    entry.itemId().equals(staged.getKey())
                            && entry.value() == staged.getValue()));
        }
        catalog.accept(withPending(incoming));

        List<CashierCatalogPayload.Entry> entries = catalog.entries();
        int first = scroll * COLUMNS;
        for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
            int index = first + cell;
            if (index >= entries.size()) {
                break;
            }
            CashierCatalogPayload.Entry entry = entries.get(index);
            int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
            int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;

            boolean chosen = entry.itemId().equals(selected);
            boolean hovered = isOver(mouseX, mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2);
            graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                    chosen ? 0x60FFD54F : hovered ? 0x40FFFFFF : 0x20000000);

            Optional<Item> item = ItemIds.item(entry.itemId());
            item.ifPresent(found -> graphics.renderItem(new ItemStack(found), x + 2, y + 2));

            // Yellow for staged, blue for a datapack price. Marked rather
            // than hidden in both cases: a datapack entry is a real price the
            // cashier honors, and a staged one is a number that is on screen
            // but not yet true.
            int color = pending.containsKey(entry.itemId()) ? 0xFFFFD54F
                    : entry.fromDatapack() ? 0xFF7986CB : 0xFFFFFFFF;
            graphics.drawString(font, CreditFormat.of(entry.value()),
                    x + 22, y + 6, color, false);
        }
    }

    /**
     * The catalog as the screen is showing it: staged values over the real
     * ones, and staged items that are not priced yet appended.
     */
    private List<CashierCatalogPayload.Entry> withPending(
            List<CashierCatalogPayload.Entry> live) {
        if (pending.isEmpty()) {
            return live;
        }
        List<CashierCatalogPayload.Entry> shown = new ArrayList<>(live.size() + pending.size());
        for (CashierCatalogPayload.Entry entry : live) {
            Long staged = pending.get(entry.itemId());
            shown.add(staged == null ? entry : new CashierCatalogPayload.Entry(
                    entry.itemId(), staged, entry.buyback(), entry.fromDatapack()));
        }
        pending.forEach((itemId, staged) -> {
            if (live.stream().noneMatch(entry -> entry.itemId().equals(itemId))) {
                // Not priced yet, so it has no buyback to quote. Zero is
                // honest here: nothing can be bought back at a price the
                // server has not accepted.
                shown.add(new CashierCatalogPayload.Entry(itemId, staged, 0, false));
            }
        });
        return shown;
    }

    private void renderControls(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean searching = search != null && search.isFocused();
        drawControl(graphics, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W,
                searching ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, searching);
        if (search != null) {
            search.render(graphics, mouseX, mouseY, 0f);
        }

        int sortX = leftPos + GRID_X + SEARCH_W + 4;
        drawControl(graphics, sortX, topPos + SEARCH_Y, SORT_W, Panels.OUTLINE,
                0xFF3A3A3A, isOver(mouseX, mouseY, sortX, topPos + SEARCH_Y, SORT_W, SEARCH_H));
        Component sortLabel = Component.literal(catalog.descending() ? "▼ " : "▲ ")
                .append(catalog.sortBy().label());
        graphics.drawString(font, sortLabel,
                sortX + (SORT_W - font.width(sortLabel)) / 2, topPos + SEARCH_Y + TEXT_Y,
                0xFFFFFFFF, false);

        boolean typing = value != null && value.isFocused();
        drawControl(graphics, leftPos + VALUE_X, topPos + ACTION_Y, VALUE_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);
        if (value != null) {
            value.render(graphics, mouseX, mouseY, 0f);
        }

        int actionX = leftPos + ACTION_X;
        int actionY = topPos + ACTION_Y;
        boolean ready = canAct();
        drawControl(graphics, actionX, actionY, ACTION_W, Panels.OUTLINE,
                ready ? 0xFF2E7D32 : 0xFF4A4A4A,
                ready && isOver(mouseX, mouseY, actionX, actionY, ACTION_W, SEARCH_H));
        Component actionLabel = Component.translatable(selected.equals(NO_SELECTION)
                ? "tablegames.admin.cashier.price"
                : "tablegames.admin.cashier.set_value");
        graphics.drawString(font, actionLabel,
                actionX + (ACTION_W - font.width(actionLabel)) / 2, actionY + TEXT_Y,
                ready ? 0xFFFFFFFF : 0xFF9A9A9A, false);

        renderApplyButton(graphics, mouseX, mouseY);
        renderRemoveButton(graphics, mouseX, mouseY);
        renderSpreadRow(graphics, mouseX, mouseY);
    }

    /** Drawn only when something is staged, since it is the only way out. */
    private void renderApplyButton(GuiGraphics graphics, int mouseX, int mouseY) {
        if (pending.isEmpty()) {
            return;
        }
        int x = leftPos + APPLY_X;
        int y = topPos + REMOVE_Y;
        drawControl(graphics, x, y, APPLY_W, Panels.OUTLINE, 0xFF2E7D32,
                isOver(mouseX, mouseY, x, y, APPLY_W, SEARCH_H));

        Component label = Component.translatable(
                "tablegames.admin.cashier.apply_pending", pending.size());
        graphics.drawString(font, label, x + (APPLY_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);
    }

    /** Drawn only with an entry selected, and red once it is armed. */
    private void renderRemoveButton(GuiGraphics graphics, int mouseX, int mouseY) {
        if (selected.equals(NO_SELECTION)) {
            return;
        }
        int x = leftPos + ACTION_X;
        int y = topPos + REMOVE_Y;
        boolean armed = isArmed();
        drawControl(graphics, x, y, ACTION_W, Panels.OUTLINE,
                armed ? 0xFFB71C1C : 0xFF6D4C41,
                isOver(mouseX, mouseY, x, y, ACTION_W, SEARCH_H));

        Component label = Component.translatable(armed
                ? "tablegames.admin.shop.remove_confirm"
                : "tablegames.admin.shop.remove");
        graphics.drawString(font, label, x + (ACTION_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);
    }

    private void renderSpreadRow(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, Component.translatable("tablegames.admin.cashier.spread"),
                leftPos + GRID_X, topPos + SPREAD_Y + TEXT_Y, Panels.LABEL_TEXT, false);

        boolean typing = spread != null && spread.isFocused();
        drawControl(graphics, leftPos + SPREAD_X, topPos + SPREAD_Y, SPREAD_W,
                typing ? 0xFFFFFFFF : 0xFFA0A0A0, 0xFF000000, typing);
        if (spread != null) {
            spread.render(graphics, mouseX, mouseY, 0f);
        }

        int x = leftPos + SPREAD_BUTTON_X;
        int y = topPos + SPREAD_Y;
        drawControl(graphics, x, y, SPREAD_BUTTON_W, Panels.OUTLINE, 0xFF3A3A3A,
                isOver(mouseX, mouseY, x, y, SPREAD_BUTTON_W, SEARCH_H));
        Component label = Component.translatable("tablegames.admin.cashier.apply");
        graphics.drawString(font, label, x + (SPREAD_BUTTON_W - font.width(label)) / 2,
                y + TEXT_Y, 0xFFFFFFFF, false);
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

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
            CashierCatalogPayload.Entry entry = entries.get(index);
            List<Component> lines = new ArrayList<>();
            lines.add(ItemIds.displayName(entry.itemId()));
            lines.add(Component.translatable("tablegames.admin.cashier.pays",
                    CreditFormat.of(entry.value())).withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("tablegames.admin.cashier.charges",
                    CreditFormat.of(entry.buyback())).withStyle(ChatFormatting.GRAY));
            if (entry.fromDatapack()) {
                lines.add(Component.translatable("tablegames.admin.cashier.datapack")
                        .withStyle(ChatFormatting.BLUE));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (focusField(search, leftPos + GRID_X, topPos + SEARCH_Y, SEARCH_W, mx, my, button)
                || focusField(value, leftPos + VALUE_X, topPos + ACTION_Y, VALUE_W, mx, my, button)
                || focusField(spread, leftPos + SPREAD_X, topPos + SPREAD_Y,
                        SPREAD_W, mx, my, button)) {
            return true;
        }

        int sortX = leftPos + GRID_X + SEARCH_W + 4;
        if (isOver(mx, my, sortX, topPos + SEARCH_Y, SORT_W, SEARCH_H)) {
            if (button == 1) {
                catalog.toggleDirection();
            } else {
                catalog.cycleSort();
            }
            scroll = 0;
            playClick();
            return true;
        }
        if (isOver(mx, my, leftPos + ACTION_X, topPos + ACTION_Y, ACTION_W, SEARCH_H)) {
            act();
            return true;
        }
        if (!selected.equals(NO_SELECTION)
                && isOver(mx, my, leftPos + ACTION_X, topPos + REMOVE_Y, ACTION_W, SEARCH_H)) {
            remove();
            return true;
        }
        if (!pending.isEmpty()
                && isOver(mx, my, leftPos + APPLY_X, topPos + REMOVE_Y, APPLY_W, SEARCH_H)) {
            applyPending();
            return true;
        }
        if (isOver(mx, my, leftPos + SPREAD_BUTTON_X, topPos + SPREAD_Y,
                SPREAD_BUTTON_W, SEARCH_H)) {
            applySpread();
            return true;
        }
        if (clickedEntry(mx, my)) {
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

    /** Selects an entry or deselects it when it was already chosen. */
    private boolean clickedEntry(int mouseX, int mouseY) {
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
            CashierCatalogPayload.Entry entry = entries.get(index);
            selected = entry.itemId().equals(selected) ? NO_SELECTION : entry.itemId();
            value.setValue(selected.equals(NO_SELECTION) ? "" : String.valueOf(entry.value()));
            // Changing the subject disarms the button, so a click meant for
            // one entry cannot land, armed, on the next.
            confirmUntil = 0;
            playClick();
            return true;
        }
        return false;
    }

    private boolean canAct() {
        if (typed(value) < 1) {
            return false;
        }
        return !selected.equals(NO_SELECTION) || !menu.held().isEmpty();
    }

    /**
     * Stages a value rather than sending it.
     * <p>
     * One extra click for a single edit, and the only way to make a linked
     * pair movable at all. It also means a set can be read over before it is
     * committed, which for a table of prices is worth having on its own.
     */
    private void act() {
        if (!canAct()) {
            return;
        }
        String itemId = selected.equals(NO_SELECTION)
                ? ItemIds.idOf(menu.held())
                : selected;
        if (itemId.isEmpty()) {
            return;
        }
        if (pending.size() >= AdminCashierBatchPayload.MAX_CHANGES
                && !pending.containsKey(itemId)) {
            return;
        }
        pending.put(itemId, typed(value));
        selected = NO_SELECTION;
        value.setValue("");
        playClick();
    }

    /** Sends everything staged as one change, for the server to take or refuse. */
    private void applyPending() {
        if (pending.isEmpty()) {
            return;
        }
        List<AdminCashierBatchPayload.Change> changes = new ArrayList<>(pending.size());
        pending.forEach((itemId, staged) ->
                changes.add(new AdminCashierBatchPayload.Change(itemId, staged)));
        PacketDistributor.sendToServer(new AdminCashierBatchPayload(List.copyOf(changes)));
        // Cleared on the catalog that comes back, not here. If the server
        // refuses the set, the staged values are still on screen to fix.
        playClick();
    }

    /**
     * Arms the remove button, then clears the price on the second click.
     * <p>
     * Two clicks because taking a price away is not the inverse of setting
     * one: anything already converted stays converted, and a player mid-trade
     * sees the item vanish from the cashier.
     */
    private void remove() {
        if (selected.equals(NO_SELECTION)) {
            return;
        }
        if (!isArmed()) {
            confirmUntil = System.currentTimeMillis() + CONFIRM_MILLIS;
            playClick();
            return;
        }
        PacketDistributor.sendToServer(AdminCashierActionPayload.remove(selected));
        selected = NO_SELECTION;
        value.setValue("");
        confirmUntil = 0;
        playClick();
    }

    private void applySpread() {
        PacketDistributor.sendToServer(
                AdminCashierActionPayload.spread((int) Math.min(100, typed(spread))));
        playClick();
    }

    private boolean isArmed() {
        return System.currentTimeMillis() < confirmUntil;
    }

    private long typed(EditBox field) {
        if (field == null || field.getValue().isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(field.getValue());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int totalRows = (catalog.size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > ROWS) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, totalRows - ROWS);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    /** The same one-pixel outline and hover wash the other screens use. */
    private void drawControl(GuiGraphics graphics, int x, int y, int width,
                             int outline, int face, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + SEARCH_H + 1, outline);
        graphics.fill(x, y, x + width, y + SEARCH_H, hovered ? face + 0x00191919 : face);
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
}
