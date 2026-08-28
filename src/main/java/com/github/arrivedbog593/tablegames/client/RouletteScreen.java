package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.PocketColor;
import com.github.arrivedbog593.tablegames.platform.network.RouletteActionPayload;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The roulette layout.
 * <p>
 * Sized to the felt rather than to an inventory, which is the whole reason
 * table games use a plain screen. It draws and sends wagers; it decides
 * nothing. The balance arrives from the server, the wheel is spun there, and
 * every bet is rechecked before it counts.
 * <p>
 * Chips and buttons sit on separate rows. Cramming them onto one fits in
 * English and overflows the moment a translation is longer, which is not a
 * thing to discover from a screenshot.
 */
public class RouletteScreen extends TableScreen {

    /** Chip denominations, smallest first. */
    private static final long[] CHIPS = {10, 50, 100, 500, 1000, 5000};

    private static final int PANEL_W = 262;
    private static final int PANEL_H = 162;

    private static final int NUMBER_W = 15;
    private static final int NUMBER_H = 15;
    private static final int GRID_X = 26;
    private static final int GRID_Y = 32;
    private static final int ZERO_W = 16;
    private static final int COLUMN_W = 18;

    private static final int CHIP_W = 30;
    private static final int CHIP_H = 16;
    private static final int CHIP_ROW_Y = 112;

    private static final int BUTTON_W = 92;
    private static final int BUTTON_ROW_Y = 136;

    private static final int RED = 0xFFB3212B;
    private static final int BLACK = 0xFF1C1C1C;
    private static final int GREEN = 0xFF1E7A46;
    private static final int OUTSIDE = 0xFF2A6B49;
    private static final int WINNER = 0xFFFFD54F;

    /** Red numbers on a standard wheel. Fixed by convention, both variants. */
    private static final int[] RED_NUMBERS = {
            1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
    };

    private int chipIndex = 2;
    private final List<Spot> spots = new ArrayList<>();

    /** A clickable region of the felt and the wager it stands for. */
    private record Spot(int x, int y, int w, int h, BetType type, int number, boolean doubleZero) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        }
    }

    public RouletteScreen(BlockPos tablePos) {
        super(Component.translatable("tablegames.game.roulette"), tablePos, PANEL_W, PANEL_H);
    }

    @Override
    protected void init() {
        super.init();
        buildSpots();
    }

    /**
     * Lays out the felt once, in screen coordinates.
     * <p>
     * The grid follows the real thing: three rows running 3-6-9 across the
     * top, 2-5-8 in the middle, 1-4-7 along the bottom, so the three column
     * bets line up where players expect them.
     */
    private void buildSpots() {
        spots.clear();
        int gridX = left + GRID_X;
        int gridY = top + GRID_Y;

        spots.add(new Spot(left + 8, gridY, ZERO_W, 3 * NUMBER_H,
                BetType.STRAIGHT_UP, 0, false));

        for (int column = 0; column < 12; column++) {
            for (int row = 0; row < 3; row++) {
                spots.add(new Spot(
                        gridX + column * NUMBER_W, gridY + row * NUMBER_H,
                        NUMBER_W, NUMBER_H,
                        BetType.STRAIGHT_UP, 3 - row + column * 3, false));
            }
        }

        BetType[] columns = {BetType.COLUMN_THIRD, BetType.COLUMN_SECOND, BetType.COLUMN_FIRST};
        for (int row = 0; row < 3; row++) {
            spots.add(new Spot(gridX + 12 * NUMBER_W, gridY + row * NUMBER_H,
                    COLUMN_W, NUMBER_H, columns[row], 0, false));
        }

        int dozenY = gridY + 3 * NUMBER_H;
        BetType[] dozens = {BetType.DOZEN_FIRST, BetType.DOZEN_SECOND, BetType.DOZEN_THIRD};
        for (int i = 0; i < 3; i++) {
            spots.add(new Spot(gridX + i * 60, dozenY, 60, NUMBER_H, dozens[i], 0, false));
        }

        int evenY = dozenY + NUMBER_H;
        BetType[] evens = {BetType.LOW, BetType.EVEN, BetType.RED,
                BetType.BLACK, BetType.ODD, BetType.HIGH};
        for (int i = 0; i < 6; i++) {
            spots.add(new Spot(gridX + i * 30, evenY, 30, NUMBER_H, evens[i], 0, false));
        }
    }

    private int buttonX(int index) {
        return left + PANEL_W - 8 - (2 - index) * (BUTTON_W + 4);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        RouletteStatePayload state = ClientRouletteState.state();

        drawPanel(graphics, left, top, left + PANEL_W, top + PANEL_H);
        drawRecess(graphics, left + 7, top + GRID_Y - 1, PANEL_W - 14, 5 * NUMBER_H + 2);

        renderHeader(graphics, state);
        renderFelt(graphics, mouseX, mouseY, state);
        renderControls(graphics, mouseX, mouseY, state);
        renderHover(graphics, mouseX, mouseY);
    }

    private void renderHeader(GuiGraphics graphics, RouletteStatePayload state) {
        graphics.drawString(font, title, left + 8, top + 7, LABEL_TEXT, false);

        Component balance = Component.translatable(
                "tablegames.roulette.balance", format(state.balance()));
        graphics.drawString(font, balance,
                left + PANEL_W - 8 - font.width(balance), top + 7, 0xFF2E7D32, false);

        Component middle;
        int colour;
        if (state.hasResult()) {
            middle = Component.translatable("tablegames.roulette.result", state.resultLabel());
            colour = 0xFFB3212B;
        } else if (state.secondsLeft() > 0) {
            middle = Component.translatable("tablegames.roulette.closing", state.secondsLeft());
            colour = LABEL_TEXT;
        } else {
            long staked = ClientRouletteState.wagered();
            middle = staked > 0
                    ? Component.translatable("tablegames.roulette.staked", format(staked))
                    : Component.translatable("tablegames.roulette.place_your_bets");
            colour = staked > 0 ? 0xFF8A4B00 : LABEL_TEXT;
        }
        graphics.drawString(font, middle,
                left + (PANEL_W - font.width(middle)) / 2, top + 20, colour, false);
    }

    private void renderFelt(GuiGraphics graphics, int mouseX, int mouseY,
                            RouletteStatePayload state) {
        for (Spot spot : spots) {
            int x1 = spot.x() + spot.w() - 1;
            int y1 = spot.y() + spot.h() - 1;
            graphics.fill(spot.x(), spot.y(), x1, y1, colourOf(spot));

            String label = labelOf(spot);
            if (!label.isEmpty()) {
                graphics.drawString(font, label,
                        spot.x() + (spot.w() - font.width(label)) / 2,
                        spot.y() + (spot.h() - 8) / 2, 0xFFFFFFFF, false);
            }

            if (isWinner(spot, state)) {
                // A ring rather than a wash, so the number underneath stays
                // readable while the ball is being shown.
                graphics.fill(spot.x(), spot.y(), x1, spot.y() + 1, WINNER);
                graphics.fill(spot.x(), y1 - 1, x1, y1, WINNER);
                graphics.fill(spot.x(), spot.y(), spot.x() + 1, y1, WINNER);
                graphics.fill(x1 - 1, spot.y(), x1, y1, WINNER);
            }

            if (stakedOn(spot) > 0) {
                // A bright pip in the corner rather than a number: at fifteen
                // pixels wide there is no room for both.
                graphics.fill(spot.x() + spot.w() - 6, spot.y() + 1,
                        spot.x() + spot.w() - 2, spot.y() + 5, WINNER);
            }

            if (state.bettingOpen() && spot.contains(mouseX, mouseY)) {
                graphics.fill(spot.x(), spot.y(), x1, y1, 0x50FFFFFF);
            }
        }
    }

    private void renderControls(GuiGraphics graphics, int mouseX, int mouseY,
                                RouletteStatePayload state) {
        int chipY = top + CHIP_ROW_Y;
        for (int i = 0; i < CHIPS.length; i++) {
            int x = left + 8 + i * (CHIP_W + 2);
            boolean selected = i == chipIndex;
            boolean affordable = state.balance() >= CHIPS[i];

            drawButton(graphics, x, chipY, CHIP_W, CHIP_H,
                    selected ? 0xFFE0B33A : 0xFF9A9A9A,
                    isOver(mouseX, mouseY, x, chipY, CHIP_W, CHIP_H));

            String label = CHIPS[i] >= 1000 ? (CHIPS[i] / 1000) + "k" : String.valueOf(CHIPS[i]);
            graphics.drawString(font, label, x + (CHIP_W - font.width(label)) / 2, chipY + 4,
                    selected ? 0xFF000000 : (affordable ? 0xFFFFFFFF : 0xFFFF6B6B), false);
        }

        int buttonY = top + BUTTON_ROW_Y;
        drawLabelledButton(graphics, mouseX, mouseY, buttonX(0), buttonY,
                Component.translatable("tablegames.roulette.clear"), 0xFF8A3A3A);
        drawLabelledButton(graphics, mouseX, mouseY, buttonX(1), buttonY,
                Component.translatable("tablegames.roulette.spin"), 0xFF2E7D32);
    }

    private void drawLabelledButton(GuiGraphics graphics, int mouseX, int mouseY,
                                    int x, int y, Component label, int face) {
        drawButton(graphics, x, y, BUTTON_W, CHIP_H, face,
                isOver(mouseX, mouseY, x, y, BUTTON_W, CHIP_H));
        graphics.drawString(font, label,
                x + (BUTTON_W - font.width(label)) / 2, y + 4, 0xFFFFFFFF, false);
    }

    private void renderHover(GuiGraphics graphics, int mouseX, int mouseY) {
        for (Spot spot : spots) {
            if (!spot.contains(mouseX, mouseY)) {
                continue;
            }
            List<Component> lines = new ArrayList<>();
            lines.add(Component.translatable(spot.type().translationKey()));
            lines.add(Component.translatable("tablegames.roulette.pays",
                    spot.type().payoutRatio()).withStyle(ChatFormatting.GRAY));
            long staked = stakedOn(spot);
            if (staked > 0) {
                lines.add(Component.translatable("tablegames.roulette.your_stake",
                        format(staked)).withStyle(ChatFormatting.GOLD));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        int chipY = top + CHIP_ROW_Y;
        for (int i = 0; i < CHIPS.length; i++) {
            int x = left + 8 + i * (CHIP_W + 2);
            if (isOver(mx, my, x, chipY, CHIP_W, CHIP_H)) {
                chipIndex = i;
                click();
                return true;
            }
        }

        int buttonY = top + BUTTON_ROW_Y;
        if (isOver(mx, my, buttonX(0), buttonY, BUTTON_W, CHIP_H)) {
            send(RouletteActionPayload.clear(tablePos));
            return true;
        }
        if (isOver(mx, my, buttonX(1), buttonY, BUTTON_W, CHIP_H)) {
            send(RouletteActionPayload.spin(tablePos));
            return true;
        }

        if (ClientRouletteState.state().bettingOpen()) {
            for (Spot spot : spots) {
                if (spot.contains(mx, my)) {
                    send(RouletteActionPayload.place(tablePos, spot.type(),
                            spot.type().requiresTarget() ? pocketOf(spot) : null,
                            CHIPS[chipIndex]));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** The mouse wheel steps through chip values, like reaching for a stack. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        chipIndex = Math.clamp(chipIndex + (int) Math.signum(deltaY), 0, CHIPS.length - 1);
        return true;
    }

    // --- Helpers ----------------------------------------------------------------

    /** Whether the ball landed on this exact spot. Straight-up bets only. */
    private static boolean isWinner(Spot spot, RouletteStatePayload state) {
        return state.hasResult()
                && spot.type() == BetType.STRAIGHT_UP
                && spot.number() == state.resultNumber()
                && spot.doubleZero() == state.resultDoubleZero();
    }

    private static Pocket pocketOf(Spot spot) {
        if (spot.number() == 0) {
            return spot.doubleZero() ? Pocket.doubleZeroPocket() : Pocket.zero();
        }
        return Pocket.of(spot.number(),
                isRed(spot.number()) ? PocketColor.RED : PocketColor.BLACK);
    }

    private static boolean isRed(int number) {
        for (int red : RED_NUMBERS) {
            if (red == number) {
                return true;
            }
        }
        return false;
    }

    private static int colourOf(Spot spot) {
        if (spot.type() == BetType.STRAIGHT_UP) {
            if (spot.number() == 0) {
                return GREEN;
            }
            return isRed(spot.number()) ? RED : BLACK;
        }
        return switch (spot.type()) {
            case RED -> RED;
            case BLACK -> BLACK;
            default -> OUTSIDE;
        };
    }

    private static String labelOf(Spot spot) {
        if (spot.type() == BetType.STRAIGHT_UP) {
            return spot.number() == 0
                    ? (spot.doubleZero() ? "00" : "0")
                    : String.valueOf(spot.number());
        }
        return switch (spot.type()) {
            case COLUMN_FIRST, COLUMN_SECOND, COLUMN_THIRD -> "2:1";
            case DOZEN_FIRST -> "1-12";
            case DOZEN_SECOND -> "13-24";
            case DOZEN_THIRD -> "25-36";
            case LOW -> "1-18";
            case HIGH -> "19-36";
            case EVEN -> "EVEN";
            case ODD -> "ODD";
            default -> "";
        };
    }

    /** What this player has on that exact spot. */
    private static long stakedOn(Spot spot) {
        long total = 0;
        for (RouletteStatePayload.Wager wager : ClientRouletteState.state().myBets()) {
            if (wager.type() != spot.type()) {
                continue;
            }
            if (spot.type() == BetType.STRAIGHT_UP
                    && (wager.targetNumber() != spot.number()
                    || wager.targetDoubleZero() != spot.doubleZero())) {
                continue;
            }
            total += wager.amount();
        }
        return total;
    }

    private void send(RouletteActionPayload payload) {
        PacketDistributor.sendToServer(payload);
        click();
    }

    private void click() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}