package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.menu.CashierMenu;
import com.github.arrivedbog593.tablegames.platform.network.CashierCatalogPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * The cashier's screen.
 * <p>
 * Draws and sends button ids. It computes nothing that matters: the balance
 * and the tray's value arrive from the server, and every click is revalidated
 * there. A player editing this class can change what they see and nothing
 * else.
 */
public class CashierScreen extends AbstractContainerScreen<CashierMenu> {

    private static final ResourceLocation BACKGROUND = ResourceLocation
            .fromNamespaceAndPath(TableGames.MOD_ID, "textures/gui/cashier.png");

    /** Catalogue rows drawn at once. */
    private static final int VISIBLE_ROWS = 4;
    private static final int ROW_HEIGHT = 20;
    private static final int LIST_X = 76;
    private static final int LIST_Y = 28;
    private static final int LIST_WIDTH = 92;

    private static final int CONVERT_X = 8;
    private static final int CONVERT_Y = 88;
    private static final int CONVERT_WIDTH = 62;
    private static final int CONVERT_HEIGHT = 18;

    private int scroll;

    public CashierScreen(CashierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 205;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(BACKGROUND, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderCatalog(graphics, mouseX, mouseY);
        renderConvertButton(graphics, mouseX, mouseY);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0x404040, false);
        graphics.drawString(font, playerInventoryTitle,
                inventoryLabelX, inventoryLabelY, 0x404040, false);
        graphics.drawString(font, Component.translatable(
                        "tablegames.cashier.balance", format(menu.balance())),
                8, 17, 0x2E7D32, false);
    }

    private void renderConvertButton(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = leftPos + CONVERT_X;
        int y = topPos + CONVERT_Y;
        boolean enabled = menu.depositValue() > 0;
        boolean hovered = enabled && isOver(mouseX, mouseY, x, y, CONVERT_WIDTH, CONVERT_HEIGHT);

        int fill = !enabled ? 0xFF5A5A5A : hovered ? 0xFF4CAF50 : 0xFF388E3C;
        graphics.fill(x, y, x + CONVERT_WIDTH, y + CONVERT_HEIGHT, fill);
        graphics.renderOutline(x, y, CONVERT_WIDTH, CONVERT_HEIGHT, 0xFF1B1B1B);

        Component label = enabled
                ? Component.literal("+" + format(menu.depositValue()))
                : Component.translatable("tablegames.cashier.convert");
        int textX = x + (CONVERT_WIDTH - font.width(label)) / 2;
        graphics.drawString(font, label, textX, y + 5, 0xFFFFFF, false);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        List<CashierCatalogPayload.Entry> entries = ClientCashierState.entries();
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;

        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("tablegames.cashier.no_items"),
                    x, y, 0x808080, false);
            return;
        }

        int shown = Math.min(VISIBLE_ROWS, entries.size() - scroll);
        for (int row = 0; row < shown; row++) {
            CashierCatalogPayload.Entry entry = entries.get(scroll + row);
            int rowY = y + row * ROW_HEIGHT;
            boolean hovered = isOver(mouseX, mouseY, x, rowY, LIST_WIDTH, ROW_HEIGHT - 2);
            boolean affordable = menu.balance() >= entry.value();

            graphics.fill(x, rowY, x + LIST_WIDTH, rowY + ROW_HEIGHT - 2,
                    hovered ? 0x40FFFFFF : 0x20000000);

            Optional<Item> item = ItemIds.item(entry.itemId());
            item.ifPresent(value -> graphics.renderItem(new ItemStack(value), x + 2, rowY + 1));

            graphics.drawString(font, Component.literal(format(entry.value())),
                    x + 22, rowY + 5, affordable ? 0xFFFFFF : 0xFF6B6B, false);
        }

        if (scroll > 0) {
            graphics.drawString(font, "▲", x + LIST_WIDTH - 8, y - 8, 0xFFFFFF, false);
        }
        if (scroll + VISIBLE_ROWS < entries.size()) {
            graphics.drawString(font, "▼",
                    x + LIST_WIDTH - 8, y + VISIBLE_ROWS * ROW_HEIGHT, 0xFFFFFF, false);
        }
    }

    @Override
    protected void renderTooltip(@NotNull GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        List<CashierCatalogPayload.Entry> entries = ClientCashierState.entries();
        int x = leftPos + LIST_X;
        int y = topPos + LIST_Y;
        int shown = Math.clamp(entries.size() - scroll, 0, VISIBLE_ROWS);

        for (int row = 0; row < shown; row++) {
            int rowY = y + row * ROW_HEIGHT;
            if (!isOver(mouseX, mouseY, x, rowY, LIST_WIDTH, ROW_HEIGHT - 2)) {
                continue;
            }
            CashierCatalogPayload.Entry entry = entries.get(scroll + row);
            graphics.renderComponentTooltip(font, List.of(
                            ItemIds.displayName(entry.itemId()),
                            Component.translatable("tablegames.cashier.unit_price",
                                    format(entry.value())).withStyle(ChatFormatting.GRAY),
                            Component.translatable("tablegames.cashier.click_hint")
                                    .withStyle(ChatFormatting.DARK_GRAY)),
                    mouseX, mouseY);
            return;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int convertX = leftPos + CONVERT_X;
            int convertY = topPos + CONVERT_Y;
            if (menu.depositValue() > 0
                    && isOver((int) mouseX, (int) mouseY,
                    convertX, convertY, CONVERT_WIDTH, CONVERT_HEIGHT)) {
                send(CashierMenu.BUTTON_CONVERT);
                return true;
            }

            List<CashierCatalogPayload.Entry> entries = ClientCashierState.entries();
            int x = leftPos + LIST_X;
            int y = topPos + LIST_Y;
            int shown = Math.clamp(entries.size() - scroll, 0, VISIBLE_ROWS);
            for (int row = 0; row < shown; row++) {
                int rowY = y + row * ROW_HEIGHT;
                if (isOver((int) mouseX, (int) mouseY, x, rowY, LIST_WIDTH, ROW_HEIGHT - 2)) {
                    int index = scroll + row;
                    send(Screen.hasShiftDown()
                            ? CashierMenu.BUTTON_REDEEM_STACK + index
                            : CashierMenu.BUTTON_REDEEM_ONE + index);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int size = ClientCashierState.entries().size();
        if (size > VISIBLE_ROWS) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, size - VISIBLE_ROWS);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void send(int buttonId) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
            minecraft.getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    /** Thousands separators, because six-figure balances are unreadable without. */
    private static String format(long credits) {
        return String.format("%,d", credits);
    }
}
