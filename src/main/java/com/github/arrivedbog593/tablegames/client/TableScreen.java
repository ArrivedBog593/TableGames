package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.CloseTablePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Shared behaviour for every table screen.
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    // --- Vanilla's frame, drawn rather than textured ---------------------------

    protected static final int OUTLINE = 0xFF000000;
    protected static final int PANEL = 0xFFC6C6C6;
    protected static final int BEVEL_LIGHT = 0xFFFFFFFF;
    protected static final int BEVEL_DARK = 0xFF555555;
    protected static final int SLOT_FILL = 0xFF8B8B8B;
    protected static final int SLOT_DARK = 0xFF373737;
    protected static final int LABEL_TEXT = 0xFF404040;

    private static final int BEVEL = 2;

    private static int tightCorner(int d) {
        return Math.max(0, 2 - d);
    }

    private static int wideCorner(int d) {
        return Math.max(0, 3 - d);
    }

    /**
     * Vanilla's frame, from the outside in: one pixel of black outline, two of
     * light bevel, the fill, two of dark bevel, one of black. Drawn rather
     * than textured so a screen can be any size without a matching PNG.
     */
    protected void drawPanel(GuiGraphics g, int x0, int y0, int x1, int y1) {
        for (int y = y0; y < y1; y++) {
            int dTop = y - y0;
            int dBot = y1 - 1 - y;

            int l = x0 + Math.max(tightCorner(dTop), wideCorner(dBot));
            int r = x1 - Math.max(wideCorner(dTop), tightCorner(dBot));
            if (l >= r) {
                continue;
            }

            g.fill(l, y, l + 1, y + 1, OUTLINE);
            g.fill(r - 1, y, r, y + 1, OUTLINE);

            int innerL = l + 1;
            int innerR = r - 1;
            if (innerL >= innerR) {
                continue;
            }

            if (dTop == 0 || dBot == 0) {
                g.fill(innerL, y, innerR, y + 1, OUTLINE);
            } else if (dTop == 1) {
                g.fill(innerL, y, innerR, y + 1, BEVEL_LIGHT);
            } else if (dBot == 1) {
                g.fill(innerL, y, innerR, y + 1, BEVEL_DARK);
            } else if (dTop == 2) {
                g.fill(innerL, y, innerR - 1, y + 1, BEVEL_LIGHT);
                g.fill(innerR - 1, y, innerR, y + 1, PANEL);
            } else if (dBot == 2) {
                g.fill(innerL, y, innerL + 1, y + 1, PANEL);
                g.fill(innerL + 1, y, innerR, y + 1, BEVEL_DARK);
            } else {
                int lw = dTop == 3 ? 3 : BEVEL;
                int rw = dBot == 3 ? 3 : BEVEL;
                int fillL = Math.min(innerL + lw, innerR);
                int fillR = Math.max(innerR - rw, fillL);
                g.fill(innerL, y, fillL, y + 1, BEVEL_LIGHT);
                g.fill(fillL, y, fillR, y + 1, PANEL);
                g.fill(fillR, y, innerR, y + 1, BEVEL_DARK);
            }
        }
    }

    /** A sunken well. Each bevel is a continuous L that owns its own corner. */
    protected void drawRecess(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT_FILL);
        g.fill(x, y, x + w - 1, y + 1, SLOT_DARK);
        g.fill(x, y + 1, x + 1, y + h - 1, SLOT_DARK);
        g.fill(x + 1, y + h - 1, x + w, y + h, BEVEL_LIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BEVEL_LIGHT);
    }

    /** A raised button, bevelled the other way round from a well. */
    protected void drawButton(GuiGraphics g, int x, int y, int w, int h,
                              int face, boolean hovered) {
        g.fill(x, y, x + w, y + h, face);
        g.fill(x, y, x + w - 1, y + 1, BEVEL_LIGHT);
        g.fill(x, y + 1, x + 1, y + h - 1, BEVEL_LIGHT);
        g.fill(x + 1, y + h - 1, x + w, y + h, BEVEL_DARK);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BEVEL_DARK);
        if (hovered) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0x30FFFFFF);
        }
    }

    protected static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    protected static String format(long credits) {
        return String.format("%,d", credits);
    }
}
