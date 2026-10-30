package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.economycore.client.Panels;
import com.github.arrivedbog593.economycore.platform.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.network.CloseTablePayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

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
 * closes it when the player wanders off or the table is broken. Both are
 * handled here so no individual game has to remember.
 */
public abstract class TableScreen extends Screen {

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
        // betting on a table they cannot see, or stare at one that was broken.
        if (TablePresence.lost(minecraft, tablePos)) {
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

    // --- Notices ---------------------------------------------------------------

    /** Light red: a refusal, not an error in the mod. */
    private static final int NOTICE_TEXT = 0xFF8A80;
    private static final int NOTICE_FILL = 0xE0101010;
    private static final int NOTICE_WIDTH = 260;
    private static final long NOTICE_FADE_MILLIS = 500;

    private List<FormattedCharSequence> noticeLines = List.of();
    private long noticeShownAt;
    private long noticeMillis;

    /**
     * Shows why the server turned the player down, inside the screen.
     * <p>
     * Here because this is where the player is looking: the action bar is
     * drawn underneath an open screen. Long enough to read — longer for a
     * longer message — and replaced by the next one rather than stacked.
     */
    public void showNotice(Component message) {
        noticeLines = font.split(message, Math.min(NOTICE_WIDTH, width - 20));
        noticeShownAt = System.currentTimeMillis();
        noticeMillis = Math.clamp(2_500L + 45L * message.getString().length(), 3_000L, 9_000L);
    }

    /**
     * Draws the notice, once the screen has drawn everything of its own.
     * <p>
     * Called from {@link TableNotices} after the whole screen has rendered,
     * not from {@link #render}: every game screen draws its own panel after
     * calling up to here, so anything drawn here would end up underneath it.
     */
    void drawNotice(GuiGraphics graphics) {
        if (noticeLines.isEmpty()) {
            return;
        }
        long age = System.currentTimeMillis() - noticeShownAt;
        if (age >= noticeMillis) {
            noticeLines = List.of();
            return;
        }
        float alpha = Math.min(1f, (noticeMillis - age) / (float) NOTICE_FADE_MILLIS);
        int textAlpha = Math.max(4, (int) (alpha * 255)) << 24;
        int fillAlpha = (int) (alpha * (NOTICE_FILL >>> 24)) << 24;

        int widest = 0;
        for (FormattedCharSequence line : noticeLines) {
            widest = Math.max(widest, font.width(line));
        }
        int boxW = widest + 12;
        int boxH = noticeLines.size() * (font.lineHeight + 1) + 8;
        int x = (width - boxW) / 2;
        int y = 6;

        graphics.pose().pushPose();
        // Above the tooltips, which sit at 400.
        graphics.pose().translate(0, 0, 500);
        graphics.fill(x, y, x + boxW, y + boxH, fillAlpha | (NOTICE_FILL & 0xFFFFFF));
        graphics.renderOutline(x, y, boxW, boxH, fillAlpha | 0x5A2020);
        int lineY = y + 5;
        for (FormattedCharSequence line : noticeLines) {
            graphics.drawString(font, line, (width - font.width(line)) / 2, lineY,
                    textAlpha | NOTICE_TEXT, false);
            lineY += font.lineHeight + 1;
        }
        graphics.pose().popPose();
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
