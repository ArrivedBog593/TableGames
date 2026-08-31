package com.github.arrivedbog593.tablegames.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Vanilla's GUI frame, drawn from rectangles instead of a texture.
 * <p>
 * A textured panel has to be exactly the size of its PNG. That is fine until
 * a screen needs another row of controls or a wider cell, at which point the
 * choice is between editing an image for every layout change and not making
 * the change. The shop hit that wall twice in an afternoon: a price of six
 * figures ran off its cell, and the panel could not grow to fit it.
 * <p>
 * Drawn, a panel is any size for free, and the buttons on it share the same
 * bevel as the surrounding frame rather than being flat rectangles laid on
 * top of a picture.
 * <p>
 * Static because two screens need it and neither can inherit from the other:
 * one is a {@code Screen} and one an {@code AbstractContainerScreen}. Copying
 * the bevel maths into both would have been the third copy of it.
 */
public final class Panels {

    private Panels() {
    }

    public static final int OUTLINE = 0xFF000000;
    public static final int PANEL = 0xFFC6C6C6;
    public static final int BEVEL_LIGHT = 0xFFFFFFFF;
    public static final int BEVEL_DARK = 0xFF555555;
    public static final int SLOT_FILL = 0xFF8B8B8B;
    public static final int SLOT_DARK = 0xFF373737;
    public static final int LABEL_TEXT = 0xFF404040;

    /** Translucent whitewash over a hovered button. */
    public static final int HOVER_WASH = 0x30FFFFFF;

    private static final int BEVEL = 2;

    private static int tightCorner(int d) {
        return Math.max(0, 2 - d);
    }

    private static int wideCorner(int d) {
        return Math.max(0, 3 - d);
    }

    /**
     * Vanilla's frame, from the outside in: one pixel of black outline, two of
     * light bevel, the fill, two of dark bevel, one of black.
     */
    public static void panel(GuiGraphics g, int x0, int y0, int x1, int y1) {
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
    public static void recess(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, SLOT_FILL);
        g.fill(x, y, x + w - 1, y + 1, SLOT_DARK);
        g.fill(x, y + 1, x + 1, y + h - 1, SLOT_DARK);
        g.fill(x + 1, y + h - 1, x + w, y + h, BEVEL_LIGHT);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BEVEL_LIGHT);
    }

    /**
     * A raised button beveled the other way round from a well.
     * <p>
     * The mirror of {@link #recess} on purpose, and the reason both are here
     * rather than merged: light comes from the top left, so which edge is
     * bright is the whole difference between something you press and
     * something you drop things into.
     */
    public static void button(GuiGraphics g, int x, int y, int w, int h,
                              int face, boolean hovered) {
        g.fill(x, y, x + w, y + h, face);
        g.fill(x, y, x + w - 1, y + 1, BEVEL_LIGHT);
        g.fill(x, y + 1, x + 1, y + h - 1, BEVEL_LIGHT);
        g.fill(x + 1, y + h - 1, x + w, y + h, BEVEL_DARK);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, BEVEL_DARK);
        if (hovered) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, HOVER_WASH);
        }
    }

    /** An inventory slot's well, at vanilla's 18 by 18. */
    public static void slot(GuiGraphics g, int x, int y) {
        recess(g, x, y, 18, 18);
    }
}
