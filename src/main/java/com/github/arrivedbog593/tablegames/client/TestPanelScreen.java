package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.economy.CreditAccount;
import com.github.arrivedbog593.tablegames.platform.block.RigOption;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.network.OpenTestPanelPayload;
import com.github.arrivedbog593.tablegames.platform.network.TestPanelPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The test key's panel: whether this block plays with pretend money, how much
 * of it, and what the next round lands on.
 * <p>
 * Knows nothing about any particular game. What it offers as results is
 * whatever the game sent, laid out in as many columns as the labels allow —
 * a wheel's thirty-seven pockets come out as a grid in the felt's colors, a
 * machine's combinations as a list — so a game added later gets a panel
 * without this file changing.
 * <p>
 * Everything it sends is checked again on the server, which in a release
 * build ignores it entirely.
 */
public class TestPanelScreen extends Screen {

    private static final int PANEL_W = 268;
    private static final int PAD = 10;
    private static final int INNER_W = PANEL_W - 2 * PAD;
    private static final int CONTROL_H = 16;
    private static final int CELL_H = 16;
    private static final int GAP = 2;
    private static final int BUTTON_W = 70;

    private static final int GRID_Y = 118;

    private final BlockPos pos;
    private final boolean wasTest;
    private final boolean empty;
    private final long bank;
    private final long yourBalance;
    private final List<RigOption> options;

    private boolean testTable;
    private String chosen;
    private EditBox bankBox;
    private EditBox balanceBox;
    private String bankDraft;
    private String balanceDraft;

    private int left;
    private int top;
    private int panelHeight;
    private int columns;
    private int cellW;

    public TestPanelScreen(OpenTestPanelPayload payload) {
        super(Component.translatable("tablegames.debug.title",
                Component.translatable("tablegames.game." + payload.gameId())));
        this.pos = payload.pos();
        this.wasTest = payload.testTable();
        this.testTable = payload.testTable();
        this.empty = payload.empty();
        this.bank = payload.bank();
        this.yourBalance = payload.yourBalance();
        this.options = payload.options();
        this.chosen = payload.rigged();
        this.bankDraft = String.valueOf(payload.bank());
        this.balanceDraft = String.valueOf(payload.startingBalance());
    }

    public static void open(OpenTestPanelPayload payload) {
        Minecraft.getInstance().setScreen(new TestPanelScreen(payload));
    }

    @Override
    protected void init() {
        super.init();
        // As many columns as the widest label allows: a pocket number is a
        // couple of pixels, a combination a phrase.
        int widest = 0;
        for (RigOption option : options) {
            widest = Math.max(widest, font.width(option.label()));
        }
        columns = Math.max(1, Math.min(13, (INNER_W + GAP) / (widest + 10 + GAP)));
        cellW = (INNER_W - (columns - 1) * GAP) / columns;
        int rows = (options.size() + columns - 1) / columns;
        panelHeight = GRID_Y + rows * (CELL_H + GAP) + 46;
        left = (width - PANEL_W) / 2;
        top = (height - panelHeight) / 2;

        int boxW = (INNER_W - 8) / 2;
        bankBox = amountBox(left + PAD, top + 70, boxW, bankDraft, text -> bankDraft = text);
        balanceBox = amountBox(left + PAD + boxW + 8, top + 70, boxW, balanceDraft,
                text -> balanceDraft = text);
    }

    private EditBox amountBox(int x, int y, int w, String value, java.util.function.Consumer<String> onChange) {
        EditBox box = new EditBox(font, x, y, w, CONTROL_H, Component.empty());
        // Thirteen digits covers the largest balance the economy holds.
        box.setMaxLength(13);
        box.setFilter(text -> text.chars().allMatch(Character::isDigit));
        box.setValue(value);
        box.setResponder(onChange);
        // A table somebody is sitting at keeps its figures: changing them
        // starts the pretend economy over, under everybody's stacks.
        box.setEditable(empty || !wasTest);
        addRenderableWidget(box);
        return box;
    }

    // --- Checking ----------------------------------------------------------------

    private static long parse(String text) {
        try {
            long value = Long.parseLong(text);
            return value >= 0 && value <= CreditAccount.MAX_BALANCE ? value : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    /** Why this cannot be applied yet, or null when it can. */
    private Component problem() {
        if (!testTable) {
            return wasTest && !empty ? Component.translatable("tablegames.debug.table_not_empty") : null;
        }
        if (!wasTest && !empty) {
            return Component.translatable("tablegames.debug.table_not_empty");
        }
        if (parse(bankDraft) < 0 || parse(balanceDraft) < 0) {
            return Component.translatable("tablegames.debug.bad_figures");
        }
        return null;
    }

    // --- Drawing ------------------------------------------------------------------

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, left, top, left + PANEL_W, top + panelHeight);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        bankBox.visible = testTable;
        balanceBox.visible = testTable;
        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 7,
                Panels.LABEL_TEXT, false);

        Component now = wasTest
                ? Component.translatable("tablegames.debug.status_test",
                        CreditFormat.of(bank), CreditFormat.of(yourBalance))
                : Component.translatable("tablegames.debug.status_real");
        graphics.drawString(font, now, left + (PANEL_W - font.width(now)) / 2, top + 20,
                wasTest ? 0xFF7A2A9A : Panels.LABEL_TEXT, false);

        // The switch.
        int toggleY = top + 36;
        boolean toggleHover = isOver(mouseX, mouseY, left + PAD, toggleY, INNER_W, CONTROL_H);
        Panels.button(graphics, left + PAD, toggleY, INNER_W, CONTROL_H,
                testTable ? 0xFFB07AD0 : Panels.PANEL, toggleHover);
        Component toggle = Component.translatable(testTable
                ? "tablegames.debug.mode_test" : "tablegames.debug.mode_real");
        graphics.drawString(font, toggle, left + (PANEL_W - font.width(toggle)) / 2, toggleY + 4,
                Panels.LABEL_TEXT, false);

        if (testTable) {
            graphics.drawString(font, Component.translatable("tablegames.debug.bank"),
                    bankBox.getX(), top + 59, Panels.LABEL_TEXT, false);
            graphics.drawString(font, Component.translatable("tablegames.debug.balance"),
                    balanceBox.getX(), top + 59, Panels.LABEL_TEXT, false);
        } else {
            graphics.drawString(font, Component.translatable("tablegames.debug.real_hint"),
                    left + PAD, top + 66, 0xFF707070, false);
        }

        renderResults(graphics, mouseX, mouseY);
        renderFooter(graphics, mouseX, mouseY);
    }

    private void renderResults(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = top + GRID_Y - 24;
        graphics.drawString(font, Component.translatable("tablegames.debug.next_result"),
                left + PAD, y, Panels.LABEL_TEXT, false);

        // Letting it fall is always an option, and the one a table goes back to.
        int randomY = top + GRID_Y - 14;
        drawCell(graphics, left + PAD, randomY, INNER_W, Component.translatable("tablegames.debug.random"),
                0xFF8B8B8B, chosen.isEmpty(), mouseX, mouseY);

        for (int i = 0; i < options.size(); i++) {
            RigOption option = options.get(i);
            drawCell(graphics, cellX(i), cellY(i), cellW, option.label(), 0xFF000000 | option.color(),
                    option.id().equals(chosen), mouseX, mouseY);
        }
        if (!testTable) {
            // Results are only ever forced with pretend money on the table.
            graphics.fill(left + PAD, randomY, left + PAD + INNER_W,
                    cellY(options.size() - 1) + CELL_H, 0xB0C6C6C6);
        }
    }

    private void drawCell(GuiGraphics graphics, int x, int y, int w, Component label, int face,
                          boolean selected, int mouseX, int mouseY) {
        Panels.button(graphics, x, y, w, CELL_H, face, testTable && isOver(mouseX, mouseY, x, y, w, CELL_H));
        int text = luminance(face) < 0.55 ? 0xFFFFFFFF : 0xFF202020;
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 4, text, false);
        if (selected) {
            graphics.renderOutline(x - 1, y - 1, w + 2, CELL_H + 2, 0xFFFFE040);
            graphics.renderOutline(x, y, w, CELL_H, 0xFFFFE040);
        }
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY) {
        int y = footerY();
        Component problem = problem();
        if (problem != null) {
            graphics.drawString(font, problem, left + PAD, y - 12, 0xFFB02020, false);
        }
        drawButton(graphics, applyX(), y, Component.translatable("tablegames.debug.apply"),
                problem == null, mouseX, mouseY);
        drawButton(graphics, cancelX(), y, Component.translatable("gui.cancel"), true, mouseX, mouseY);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, Component label, boolean enabled,
                            int mouseX, int mouseY) {
        Panels.button(graphics, x, y, BUTTON_W, CONTROL_H, enabled ? Panels.PANEL : Panels.SLOT_FILL,
                enabled && isOver(mouseX, mouseY, x, y, BUTTON_W, CONTROL_H));
        graphics.drawString(font, label, x + (BUTTON_W - font.width(label)) / 2, y + 4,
                enabled ? Panels.LABEL_TEXT : 0xFF606060, false);
    }

    private static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    // --- Layout ---------------------------------------------------------------------

    private int cellX(int index) {
        return left + PAD + (index % columns) * (cellW + GAP);
    }

    private int cellY(int index) {
        return top + GRID_Y + 6 + (index / columns) * (CELL_H + GAP);
    }

    private int footerY() {
        return top + panelHeight - PAD - CONTROL_H;
    }

    private int applyX() {
        return left + PANEL_W - PAD - BUTTON_W;
    }

    private int cancelX() {
        return applyX() - 4 - BUTTON_W;
    }

    // --- Input ------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (isOver(mx, my, left + PAD, top + 36, INNER_W, CONTROL_H)) {
            testTable = !testTable;
            click();
            return true;
        }
        if (testTable) {
            if (isOver(mx, my, left + PAD, top + GRID_Y - 14, INNER_W, CELL_H)) {
                chosen = "";
                click();
                return true;
            }
            for (int i = 0; i < options.size(); i++) {
                if (isOver(mx, my, cellX(i), cellY(i), cellW, CELL_H)) {
                    chosen = options.get(i).id();
                    click();
                    return true;
                }
            }
        }
        int y = footerY();
        if (isOver(mx, my, cancelX(), y, BUTTON_W, CONTROL_H)) {
            click();
            onClose();
            return true;
        }
        if (isOver(mx, my, applyX(), y, BUTTON_W, CONTROL_H) && problem() == null) {
            long sentBank = testTable ? parse(bankDraft) : 0;
            long sentBalance = testTable ? parse(balanceDraft) : 0;
            PacketDistributor.sendToServer(new TestPanelPayload(pos, testTable, sentBank, sentBalance,
                    testTable ? chosen : ""));
            click();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // --- Housekeeping ---------------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (TablePresence.lost(minecraft, pos)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void click() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
