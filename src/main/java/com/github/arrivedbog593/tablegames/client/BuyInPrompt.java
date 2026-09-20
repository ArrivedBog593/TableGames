package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.table.BuyIn;
import com.github.arrivedbog593.tablegames.platform.economy.BuyInMessages;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.network.PlayerFunds;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Asking how much to bring to a table, drawn over the table's own screen.
 * <p>
 * The same box serves sitting down and topping up; only the title, the range
 * line and whether the minimum applies differ, and {@link BuyIn} already knows
 * the last of those. It reads the player's funds live rather than from a
 * snapshot, so a round settling while it is open moves the figures with it.
 * <p>
 * It checks with the same engine rule the table does and words a refusal the
 * same way, so Confirm only lights for an amount the server will take — short
 * of the funds moving between the click and the packet, which the server
 * catches regardless.
 */
final class BuyInPrompt {

    static final int W = 190;
    /** Tall enough for a refusal wrapped onto two lines above the buttons. */
    private static final int H = 112;
    private static final int PAD = 8;
    private static final int CONTROL_H = 16;
    private static final int BOX_W = 94;
    private static final int SMALL_W = 36;
    private static final int BUTTON_W = 84;

    private static final int HINT = 0xFF707070;
    private static final int ERROR = 0xFFB02020;

    private final Font font;
    private final boolean rebuy;
    private final Supplier<PlayerFunds> funds;
    private final LongConsumer onConfirm;
    private final Runnable onCancel;

    private final int left;
    private final int top;
    private final EditBox box;

    /**
     * @param rebuy     true to top up a stack, false to sit down with one
     * @param centerX   the middle of whatever the prompt covers
     * @param centerY   likewise
     * @param onConfirm handed the amount once it passes the table's rule
     */
    BuyInPrompt(Font font, boolean rebuy, int centerX, int centerY,
                Supplier<PlayerFunds> funds,
                LongConsumer onConfirm, Runnable onCancel) {
        this.font = font;
        this.rebuy = rebuy;
        this.funds = funds;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.left = centerX - W / 2;
        this.top = centerY - H / 2;

        box = new EditBox(font, left + PAD, top + 48, BOX_W, CONTROL_H,
                Component.translatable("tablegames.buyin.amount"));
        box.setMaxLength(13);
        box.setFilter(text -> text.chars().allMatch(Character::isDigit));
        box.setValue(String.valueOf(suggested()));
    }

    EditBox box() {
        return box;
    }

    boolean isRebuy() {
        return rebuy;
    }

    private Optional<BuyIn> rule() {
        return funds.get().buyIn();
    }

    private long stack() {
        return rebuy ? funds.get().stackHeld() : 0;
    }

    /**
     * What the box starts with: the minimum when sitting, if the player can
     * cover it, otherwise the most they can bring.
     */
    private long suggested() {
        return rule().map(buyIn -> {
            long largest = buyIn.largestAddition(stack(), funds.get().availableToBuy());
            return rebuy ? largest : Math.min(buyIn.minimum(), largest);
        }).orElse(0L);
    }

    private long entered() {
        try {
            return box.getValue().isEmpty() ? 0 : Long.parseLong(box.getValue());
        } catch (NumberFormatException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /** Why the entered amount would be refused, or null when it would not. */
    private Component problem() {
        Optional<BuyIn> buyIn = rule();
        if (buyIn.isEmpty()) {
            return null;
        }
        long available = funds.get().availableToBuy();
        return buyIn.get().problemWith(entered(), stack(), available)
                .map(found -> BuyInMessages.describe(buyIn.get(), found, stack(), available))
                .orElse(null);
    }

    // --- Drawing ----------------------------------------------------------------

    void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Panels.panel(graphics, left, top, left + W, top + H);

        Component title = Component.translatable(rebuy
                ? "tablegames.buyin.title_rebuy" : "tablegames.buyin.title_sit");
        graphics.drawString(font, title, left + (W - font.width(title)) / 2, top + 7,
                Panels.LABEL_TEXT, false);

        rule().ifPresent(buyIn -> {
            graphics.drawString(font, rangeLine(buyIn), left + PAD, top + 20, HINT, false);
            graphics.drawString(font, Component.translatable("tablegames.buyin.available",
                            CreditFormat.of(funds.get().availableToBuy())),
                    left + PAD, top + 31, HINT, false);
        });

        box.render(graphics, mouseX, mouseY, partialTick);
        drawButton(graphics, mouseX, mouseY, minX(), top + 48, SMALL_W,
                Component.translatable("tablegames.buyin.min"), 0xFF6A6A6A, !rebuy);
        drawButton(graphics, mouseX, mouseY, maxX(), top + 48, SMALL_W,
                Component.translatable("tablegames.buyin.max"), 0xFF6A6A6A, true);

        Component problem = problem();
        if (problem != null) {
            List<FormattedCharSequence> lines = font.split(problem, W - 2 * PAD);
            for (int i = 0; i < Math.min(2, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), left + PAD, top + 68 + i * 10,
                        ERROR, false);
            }
        } else if (funds.get().priced()) {
            // What the credits being bought actually cost. The box is in the
            // machine's own credits, and nobody should have to multiply in
            // their head to find out what is leaving their balance.
            graphics.drawString(font, Component.translatable("tablegames.buyin.price",
                            CreditFormat.of(entered()),
                            CreditFormat.of(funds.get().inBalance(entered()))),
                    left + PAD, top + 68, HINT, false);
        }

        drawButton(graphics, mouseX, mouseY, cancelX(), buttonY(), BUTTON_W,
                CommonComponents.GUI_CANCEL, 0xFF8A3A3A, true);
        drawButton(graphics, mouseX, mouseY, confirmX(), buttonY(), BUTTON_W,
                Component.translatable("tablegames.buyin.confirm"), 0xFF2E7D32, problem == null);
    }

    private Component rangeLine(BuyIn buyIn) {
        if (rebuy) {
            return buyIn.isCapped()
                    ? Component.translatable("tablegames.buyin.rebuy_cap",
                    CreditFormat.of(buyIn.maximum()), CreditFormat.of(stack()))
                    : Component.translatable("tablegames.buyin.rebuy_open",
                    CreditFormat.of(stack()));
        }
        return buyIn.isCapped()
                ? Component.translatable("tablegames.buyin.range",
                CreditFormat.of(buyIn.minimum()), CreditFormat.of(buyIn.maximum()))
                : Component.translatable("tablegames.buyin.range_open",
                CreditFormat.of(buyIn.minimum()));
    }

    private void drawButton(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w,
                            Component label, int face, boolean enabled) {
        Panels.button(graphics, x, y, w, CONTROL_H, enabled ? face : 0xFF4A4A4A,
                enabled && isOver(mouseX, mouseY, x, y, w, CONTROL_H));
        graphics.drawString(font, label, x + (w - font.width(label)) / 2, y + 4,
                enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    private int minX() {
        return left + PAD + BOX_W + 4;
    }

    private int maxX() {
        return minX() + SMALL_W + 4;
    }

    private int buttonY() {
        return top + H - 8 - CONTROL_H;
    }

    private int cancelX() {
        return left + W / 2 - 2 - BUTTON_W;
    }

    private int confirmX() {
        return left + W / 2 + 2;
    }

    // --- Input ------------------------------------------------------------------

    /** Whether the click landed on the prompt at all; everything under it is covered. */
    boolean contains(int mouseX, int mouseY) {
        return isOver(mouseX, mouseY, left, top, W, H);
    }

    /** @return true when the click was one of the prompt's own buttons */
    boolean mouseClicked(int mouseX, int mouseY) {
        if (!rebuy && isOver(mouseX, mouseY, minX(), top + 48, SMALL_W, CONTROL_H)) {
            rule().ifPresent(buyIn -> box.setValue(String.valueOf(buyIn.minimum())));
            return true;
        }
        if (isOver(mouseX, mouseY, maxX(), top + 48, SMALL_W, CONTROL_H)) {
            rule().ifPresent(buyIn -> box.setValue(String.valueOf(
                    buyIn.largestAddition(stack(), funds.get().availableToBuy()))));
            return true;
        }
        if (isOver(mouseX, mouseY, cancelX(), buttonY(), BUTTON_W, CONTROL_H)) {
            onCancel.run();
            return true;
        }
        if (isOver(mouseX, mouseY, confirmX(), buttonY(), BUTTON_W, CONTROL_H)) {
            confirm();
            return true;
        }
        return false;
    }

    /**
     * Enter in the box, or the button. Does nothing while the amount would be
     * refused.
     * <p>
     * Handed on in the balance's currency, not in the machine's credits. The
     * prompt is the last place that knows about a denomination; the packet,
     * the seat and the stack all deal in one currency.
     */
    void confirm() {
        if (problem() == null) {
            onConfirm.accept(funds.get().inBalance(entered()));
        }
    }

    void cancel() {
        onCancel.run();
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
