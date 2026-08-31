package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.CloseTablePayload;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
 * Shared behavior for every table screen.
 * <p>
 * Deliberately a plain {@link Screen} rather than a container screen. Menus
 * exist to move items between inventories, and no table game moves items:
 * cards, chips and a pot do not live in slots. Inheriting from the container
 * screen would also fix every game at the width of a nine-column inventory
 * and draw one underneath whether or not it belongs there.
 * <p>
 * The trade is that nothing tells the server when this closes, and nothing
 * closes it when the player wanders off. Both are handled here so no
 * individual game has to remember.
 */
public abstract class TableScreen extends Screen {

    /** Beyond this the player has clearly walked away from the table. */
    private static final double MAX_DISTANCE_SQUARED = 64.0;

    protected final BlockPos tablePos;

    /** Panel size in GUI pixels. Each game picks its own. */
    protected final int panelWidth;
    protected final int panelHeight;

    protected int left;
    protected int top;

    protected TableScreen(Component title, BlockPos tablePos, int panelWidth, int panelHeight) {
        super(title);
        this.tablePos = tablePos;
        this.panelWidth = panelWidth;
        this.panelHeight = panelHeight;
    }

    @Override
    protected void init() {
        super.init();
        this.left = (this.width - panelWidth) / 2;
        this.top = (this.height - panelHeight) / 2;
    }

    @Override
    public void tick() {
        super.tick();
        // A container screen gets this from stillValid. A plain one has to
        // watch for itself, or a player can walk to the next room and keep
        // betting on a table they cannot see.
        if (minecraft != null && minecraft.player != null
                && minecraft.player.distanceToSqr(tablePos.getCenter()) > MAX_DISTANCE_SQUARED) {
            onClose();
        }
    }

    @Override
    public void onClose() {
        PacketDistributor.sendToServer(new CloseTablePayload(tablePos));
        super.onClose();
    }

    /** Tables pause nothing: a betting window keeps running while you look at it. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // --- Vanilla's frame, drawn rather than textured ---------------------------

    // Kept as names here so every screen reads the same, but owned by Panels
    // now that the container screens need them too.
    protected static final int OUTLINE = Panels.OUTLINE;
    protected static final int PANEL = Panels.PANEL;
    protected static final int BEVEL_LIGHT = Panels.BEVEL_LIGHT;
    protected static final int BEVEL_DARK = Panels.BEVEL_DARK;
    protected static final int SLOT_FILL = Panels.SLOT_FILL;
    protected static final int SLOT_DARK = Panels.SLOT_DARK;
    protected static final int LABEL_TEXT = Panels.LABEL_TEXT;


    protected void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        Panels.panel(g, x0, y0, x1, y1);
    }

    protected void drawRecess(GuiGraphics g, int x, int y, int w, int h) {
        Panels.recess(g, x, y, w, h);
    }

    protected void drawButton(GuiGraphics g, int x, int y, int w, int h,
                              int face, boolean hovered) {
        Panels.button(g, x, y, w, h, face, hovered);
    }

    protected static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    protected static String format(long credits) {
        return CreditFormat.of(credits);
    }
}
