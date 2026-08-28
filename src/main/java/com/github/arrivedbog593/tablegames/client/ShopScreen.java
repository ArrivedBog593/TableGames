package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.TableGames;
import com.github.arrivedbog593.tablegames.platform.economy.ItemIds;
import com.github.arrivedbog593.tablegames.platform.menu.ShopMenu;
import com.github.arrivedbog593.tablegames.platform.network.ShopCatalogPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * The shop's screen: a grid of goods with prices.
 * <p>
 * Draws and sends button ids, nothing more. Prices shown here are for reading;
 * the server charges what its own table says.
 */
public class ShopScreen extends AbstractContainerScreen<ShopMenu> {

    private static final ResourceLocation BACKGROUND = ResourceLocation
            .fromNamespaceAndPath(TableGames.MOD_ID, "textures/gui/shop.png");

    private static final int COLUMNS = 3;
    private static final int ROWS = 4;
    private static final int CELL_WIDTH = 53;
    private static final int CELL_HEIGHT = 20;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 28;

    private int scroll;

    public ShopScreen(ShopMenu menu, Inventory inventory, Component title) {
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
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
        graphics.drawString(font, Component.translatable(
                        "tablegames.shop.balance", format(menu.balance())),
                8, 17, 0x2E7D32, false);
    }

    private void renderCatalog(GuiGraphics graphics, int mouseX, int mouseY) {
        List<ShopCatalogPayload.Entry> entries = ClientShopState.entries();
        if (entries.isEmpty()) {
            graphics.drawString(font, Component.translatable("tablegames.shop.empty"),
                    leftPos + GRID_X, topPos + GRID_Y, 0x808080, false);
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

            graphics.fill(x, y, x + CELL_WIDTH - 2, y + CELL_HEIGHT - 2,
                    hovered ? 0x50FFFFFF : 0x20000000);

            Optional<Item> item = ItemIds.item(entry.itemId());
            item.ifPresent(value -> graphics.renderItem(new ItemStack(value), x + 2, y + 2));

            graphics.drawString(font, format(entry.price()),
                    x + 21, y + 6, affordable ? 0xFFFFFF : 0xFF6B6B, false);
        }

        int totalRows = (entries.size() + COLUMNS - 1) / COLUMNS;
        if (scroll > 0) {
            graphics.drawString(font, "\u25B2",
                    leftPos + 162, topPos + GRID_Y, 0xFFFFFF, false);
        }
        if (scroll + ROWS < totalRows) {
            graphics.drawString(font, "\u25BC",
                    leftPos + 162, topPos + GRID_Y + (ROWS - 1) * CELL_HEIGHT, 0xFFFFFF, false);
        }
    }

    @Override
    protected void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        super.renderTooltip(graphics, mouseX, mouseY);

        List<ShopCatalogPayload.Entry> entries = ClientShopState.entries();
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
            graphics.renderComponentTooltip(font, List.of(
                            ItemIds.displayName(entry.itemId()),
                            Component.translatable("tablegames.shop.unit_price",
                                    format(entry.price())).withStyle(ChatFormatting.GRAY),
                            Component.translatable("tablegames.shop.click_hint")
                                    .withStyle(ChatFormatting.DARK_GRAY)),
                    mouseX, mouseY);
            return;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            List<ShopCatalogPayload.Entry> entries = ClientShopState.entries();
            int first = scroll * COLUMNS;
            for (int cell = 0; cell < COLUMNS * ROWS; cell++) {
                int index = first + cell;
                if (index >= entries.size()) {
                    break;
                }
                int x = leftPos + GRID_X + (cell % COLUMNS) * CELL_WIDTH;
                int y = topPos + GRID_Y + (cell / COLUMNS) * CELL_HEIGHT;
                if (isOver((int) mouseX, (int) mouseY, x, y, CELL_WIDTH - 2, CELL_HEIGHT - 2)) {
                    send(Screen.hasShiftDown()
                            ? ShopMenu.BUTTON_BUY_STACK + index
                            : ShopMenu.BUTTON_BUY_ONE + index);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int totalRows = (ClientShopState.entries().size() + COLUMNS - 1) / COLUMNS;
        if (totalRows > ROWS) {
            scroll = Math.clamp(scroll - (int) Math.signum(deltaY), 0, totalRows - ROWS);
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
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

    private static String format(long credits) {
        return String.format("%,d", credits);
    }
}
