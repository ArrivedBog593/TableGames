package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.games.roulette.BetType;
import com.github.arrivedbog593.tablegames.engine.games.roulette.Pocket;
import com.github.arrivedbog593.tablegames.engine.games.roulette.PocketColor;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteWheel;
import com.github.arrivedbog593.tablegames.platform.network.RouletteActionPayload;
import com.github.arrivedbog593.tablegames.platform.network.RouletteStatePayload;
import com.github.arrivedbog593.tablegames.platform.network.TableActionPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

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
 * Chips and buttons sit in separate rows. Cramming them onto one fits in
 * English and overflows the moment a translation is longer, which is not a
 * thing to discover from a screenshot.
 */
public class RouletteScreen extends TableScreen {

    /** Chip denominations, smallest first.
     * <p>
     * The denominations a real casino uses: white, red, green, black, purple,
     * yellow, brown. Worth matching rather than inventing round numbers,
     * because a chip has to mean the same thing at every table in the house —
     * and poker's will be derived from the blind, so roulette's cannot drift
     * off on their own.
     */
    private static final long[] CHIPS = {1, 5, 25, 100, 500, 1000, 5000};

    // The felt keeps its original width; the extra space on the right is the
    // seat list, which is what a spectator came to look at.
    private static final int PANEL_W = 348;
    /** The felt itself, which kept its original width when the panel grew. */
    private static final int FELT_W = 262;

    /** How long the reel is in motion, and how long the winner then sits lit. */
    private static final long SPIN_MILLIS = 2600;
    private static final long SETTLE_MILLIS = 2600;
    /** Times round the cylinder before settling. */
    private static final int TURNS = 3;
    private static final int REEL_W = 216;
    private static final int REEL_H = 26;
    private static final int CELL_W = 24;
    /** Breathing room between a cell and the window's edge. */
    private static final int CELL_PAD = 3;
    private static final int SEAT_LIST_X = 268;
    private static final int SEAT_LIST_W = 72;
    private static final int SEAT_LIST_Y = 32;
    private static final int PANEL_H = 162;

    private static final int NUMBER_W = 15;
    private static final int NUMBER_H = 15;
    private static final int GRID_X = 26;
    private static final int GRID_Y = 32;
    private static final int ZERO_W = 16;
    private static final int COLUMN_W = 18;

    private static final int CHIP_W = 26;
    private static final int CUSTOM_W = 44;
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

    /** Whether this table's wheel carries a double zero. */
    private final boolean doubleZero;

    /**
     * @param gameId     which registered game this table hosts, so the title
     *                   says which roulette it is
     * @param doubleZero true for the American wheel, which needs a second
     *                   green pocket on the layout
     */
    public RouletteScreen(BlockPos tablePos, String gameId, boolean doubleZero) {
        super(Component.translatable("tablegames.game." + gameId),
                tablePos, PANEL_W, PANEL_H);
        this.doubleZero = doubleZero;
    }

    /**
     * A typed amount, which overrides the selected chip while it holds a
     * usable number.
     * <p>
     * Six denominations cover the common stakes and nothing else. Backing
     * 4,845 with fixed chips means eight clicks and arithmetic, and "put down
     * exactly what I have left" is not expressible at all.
     */
    private EditBox customAmount;

    @Override
    protected void init() {
        super.init();
        buildSpots();

        customAmount = new EditBox(font, left + 8 + CHIPS.length * (CHIP_W + 2),
                top + CHIP_ROW_Y, CUSTOM_W, CHIP_H,
                Component.translatable("tablegames.roulette.custom_amount"));
        customAmount.setMaxLength(12);
        customAmount.setHint(Component.translatable("tablegames.roulette.custom_amount"));
        // Digits only. Rejecting the keystroke is clearer than accepting the text
        // and refusing the bet afterward.
        customAmount.setFilter(text -> text.chars().allMatch(Character::isDigit));
        addRenderableWidget(customAmount);
    }

    /**
     * What a click on the felt would stake: the typed amount when there is
     * one, otherwise the selected chip.
     */
    private long stakeToPlace() {
        if (customAmount != null && !customAmount.getValue().isEmpty()) {
            try {
                long typed = Long.parseLong(customAmount.getValue());
                if (typed > 0) {
                    return typed;
                }
            } catch (NumberFormatException tooLong) {
                // Twelve digits of nines overflows. Fall through to the chip.
                return CHIPS[chipIndex];
            }
        }
        return CHIPS[chipIndex];
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

        // The green pockets. An American wheel has two of them, and without
        // the second one there is no way to back the double zero at all —
        // which made an American table an ordinary one with a number missing.
        int greenHeight = 3 * NUMBER_H;
        if (doubleZero) {
            int half = greenHeight / 2;
            spots.add(new Spot(left + 8, gridY, ZERO_W, half,
                    BetType.STRAIGHT_UP, 0, false));
            spots.add(new Spot(left + 8, gridY + half, ZERO_W, greenHeight - half,
                    BetType.STRAIGHT_UP, 0, true));
        } else {
            spots.add(new Spot(left + 8, gridY, ZERO_W, greenHeight,
                    BetType.STRAIGHT_UP, 0, false));
        }

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
        return left + FELT_W - 8 - (2 - index) * (BUTTON_W + 4);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        RouletteStatePayload state = ClientRouletteState.state();

        drawPanel(graphics, left, top, left + PANEL_W, top + PANEL_H);
        drawRecess(graphics, left + 7, top + GRID_Y - 1, FELT_W - 14, 5 * NUMBER_H + 2);

        renderHeader(graphics, state);
        renderFelt(graphics, mouseX, mouseY, state);
        renderControls(graphics, mouseX, mouseY, state);
        // Widgets are drawn by super.render, which runs before the panel is
        // painted over them. Repainting here is what puts the field on top of
        // its own background rather than under it.
        if (customAmount != null) {
            customAmount.render(graphics, mouseX, mouseY, partialTick);
        }
        renderHover(graphics, mouseX, mouseY);
        renderWheel(graphics, state);
    }

    /**
     * The draw, for the seconds after a spin.
     * <p>
     * A reel rather than a wheel. A drawn wheel needs the eye to close
     * thirty-eight scattered squares into a circle, and at this size and this
     * palette it never does — the black pockets vanish into the background, and
     * what is left reads as a scatter of dots over the layout. A strip sliding
     * behind a window has none of those problems: it is legible at any scale,
     * the pockets keep their real cylinder order, and it suits a game drawn
     * out of rectangles.
     * <p>
     * Purely cosmetic, and openly so: the result arrived with the packet that
     * started this, the credits already moved, and the strip is running toward
     * a pocket that was decided before the first frame. Dressing that up as a
     * live draw would be dishonest — but a settled number deserves a moment of
     * theater on its way to the player, which is all this is.
     */
    private void renderWheel(GuiGraphics graphics, RouletteStatePayload state) {
        long elapsed = ClientRouletteState.sinceResult();
        if (elapsed < 0 || elapsed > SPIN_MILLIS + SETTLE_MILLIS) {
            return;
        }

        List<Pocket> ring = wheelOf().cylinder();
        int landed = wheelOf().cylinderIndexOf(state.resultDoubleZero()
                ? Pocket.doubleZeroPocket()
                : pocketFor(state.resultNumber()));
        if (landed < 0) {
            return;
        }

        // Opaque, not translucent. A layout still readable underneath turns
        // the whole thing into noise, which is exactly what sank the wheel.
        //
        // Stops below the header on purpose: the title, the running balance
        // and the announcement of the winning number all live up there, and
        // covering them would mean reprinting them here.
        graphics.fill(left + 1, top + GRID_Y - 4, left + PANEL_W - 1, top + PANEL_H - 1,
                0xF2101010);

        // The window holds a whole number of cells, and the marker sits over
        // the middle one, so the strip settles under the marker rather than
        // beside it.
        int cells = REEL_W / CELL_W | 1;
        int width = cells * CELL_W;
        int centerX = left + FELT_W / 2;
        int windowTop = top + GRID_Y + 32;
        int windowLeft = centerX - width / 2;
        drawRecess(graphics, windowLeft - 1, windowTop - 1, width + 2, REEL_H + 2);

        double progress = Math.min(1.0, elapsed / (double) SPIN_MILLIS);
        // Ease-out rather than linear: a strip that stops dead reads as a bug.
        double eased = 1 - Math.pow(1 - progress, 3);
        double traveled = eased * (TURNS * ring.size() + landed) * CELL_W;

        int firstIndex = (int) Math.floor(traveled / CELL_W);
        double frac = traveled - firstIndex * CELL_W;
        int visible = cells / 2 + 2;

        graphics.enableScissor(windowLeft, windowTop, windowLeft + width, windowTop + REEL_H);
        for (int k = -visible; k <= visible; k++) {
            int index = Math.floorMod(firstIndex + k, ring.size());
            Pocket pocket = ring.get(index);
            int x = centerX - CELL_W / 2 + k * CELL_W - (int) Math.round(frac);
            boolean isWinner = index == landed && progress >= 1.0;

            graphics.fill(x + 1, windowTop + CELL_PAD, x + CELL_W - 1,
                    windowTop + REEL_H - CELL_PAD,
                    isWinner ? 0xFFFFE066 : colorOf(pocket));
            String label = pocket.label();
            graphics.drawString(font, label,
                    x + (CELL_W - font.width(label)) / 2, windowTop + REEL_H / 2 - 4,
                    isWinner ? 0xFF000000 : 0xFFFFFFFF, false);
        }
        graphics.disableScissor();

        // The marker the strip settles under, drawn outside the window so it
        // stays put while everything behind it moves.
        graphics.fill(centerX - 1, windowTop - 5, centerX + 1, windowTop - 1, 0xFFFFE066);
        graphics.fill(centerX - 1, windowTop + REEL_H + 1, centerX + 1,
                windowTop + REEL_H + 5, 0xFFFFE066);
    }

    /** Whether the strip is still traveling toward its pocket. */
    private static boolean isReelRunning() {
        long elapsed = ClientRouletteState.sinceResult();
        return elapsed >= 0 && elapsed < SPIN_MILLIS;
    }

    private static int colorOf(Pocket pocket) {
        return switch (pocket.color()) {
            case RED -> 0xFFB03030;
            // Lighter than a real black pocket. Against the near-black
            // backdrop the true color was invisible, which left half the
            // cylinder missing from the strip.
            case BLACK -> 0xFF3A3A3A;
            case GREEN -> 0xFF2E7D32;
        };
    }

    private RouletteWheel wheelOf() {
        return doubleZero ? RouletteWheel.AMERICAN : RouletteWheel.EUROPEAN;
    }

    private Pocket pocketFor(int number) {
        for (Pocket pocket : wheelOf().pockets()) {
            if (!pocket.doubleZero() && pocket.number() == number) {
                return pocket;
            }
        }
        return Pocket.zero();
    }

    private void renderHeader(GuiGraphics graphics, RouletteStatePayload state) {
        graphics.drawString(font, title, left + 8, top + 7, LABEL_TEXT, false);

        Component balance = Component.translatable(
                "tablegames.roulette.balance", format(state.balance()));
        graphics.drawString(font, balance,
                left + FELT_W - 8 - font.width(balance), top + 7, 0xFF2E7D32, false);

        Component middle;
        int color;
        if (state.hasResult() && isReelRunning()) {
            // The strip is still moving. Printing the number up here while it
            // runs gives the answer away and makes the whole animation
            // pointless — the player reads the result and stops watching.
            middle = Component.translatable("tablegames.roulette.spinning");
            color = LABEL_TEXT;
        } else if (state.hasResult()) {
            middle = Component.translatable("tablegames.roulette.result", state.resultLabel());
            color = 0xFFB3212B;
        } else if (state.secondsLeft() > 0) {
            middle = Component.translatable("tablegames.roulette.closing", state.secondsLeft());
            color = LABEL_TEXT;
        } else {
            long staked = ClientRouletteState.wagered();
            middle = staked > 0
                    ? Component.translatable("tablegames.roulette.staked", format(staked))
                    : Component.translatable("tablegames.roulette.place_your_bets");
            color = staked > 0 ? 0xFF8A4B00 : LABEL_TEXT;
        }
        graphics.drawString(font, middle,
                left + (FELT_W - font.width(middle)) / 2, top + 20, color, false);
    }

    private void renderFelt(GuiGraphics graphics, int mouseX, int mouseY,
                            RouletteStatePayload state) {
        for (Spot spot : spots) {
            int x1 = spot.x() + spot.w() - 1;
            int y1 = spot.y() + spot.h() - 1;
            graphics.fill(spot.x(), spot.y(), x1, y1, colorOf(spot));

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

    private boolean usingCustomAmount() {
        return customAmount != null && !customAmount.getValue().isEmpty();
    }

    private void renderControls(GuiGraphics graphics, int mouseX, int mouseY,
                                RouletteStatePayload state) {
        int chipY = top + CHIP_ROW_Y;
        for (int i = 0; i < CHIPS.length; i++) {
            int x = left + 8 + i * (CHIP_W + 2);
            // Only affordability greys a chip out. A denomination below the
            // table minimum is still perfectly usable — the minimum is what a
            // whole wager has to reach, not what a single chip has to be
            // worth, and a table taking wagers from 10 up should still let
            // somebody build one out of fives.
            boolean usable = state.balance() >= CHIPS[i];
            boolean selected = i == chipIndex && !usingCustomAmount();

            drawButton(graphics, x, chipY, CHIP_W, CHIP_H,
                    selected ? 0xFFE0B33A : 0xFF9A9A9A,
                    isOver(mouseX, mouseY, x, chipY, CHIP_W, CHIP_H));

            String label = CHIPS[i] >= 1000 ? (CHIPS[i] / 1000) + "k" : String.valueOf(CHIPS[i]);
            graphics.drawString(font, label, x + (CHIP_W - font.width(label)) / 2, chipY + 4,
                    selected ? 0xFF000000 : (usable ? 0xFFFFFFFF : 0xFF7A5050), false);
        }

        int buttonY = top + BUTTON_ROW_Y;
        drawLabeledButton(graphics, mouseX, mouseY, buttonX(0), buttonY,
                Component.translatable("tablegames.roulette.clear"),
                state.isSeated() && state.bettingOpen() ? 0xFF8A3A3A : 0xFF4A3030);

        // The right-hand button changes with what the player can actually do,
        // rather than showing a spin control to somebody with no seat. There
        // is no "spin now" anymore: a single player forcing the wheel was a
        // way to close the window on everybody else the moment their own chip
        // landed.
        drawLabeledButton(graphics, mouseX, mouseY, buttonX(1), buttonY,
                rightButtonLabel(state), rightButtonColor(state));

        renderSeats(graphics, state);

        // Leaving lives beside the seat list rather than in the main row: it
        // belongs to the seat, not to the round, and putting it next to
        // "Ready" is how somebody stands up when they meant to lock in.
        if (state.isSeated()) {
            int leaveX = left + SEAT_LIST_X;
            drawButton(graphics, leaveX, buttonY, SEAT_LIST_W, CHIP_H,
                    state.locked() ? 0xFF4A4A4A : 0xFF6A4A2A,
                    !state.locked() && isOver(mouseX, mouseY, leaveX, buttonY,
                            SEAT_LIST_W, CHIP_H));
            Component leave = Component.translatable("tablegames.table.stand_up");
            graphics.drawString(font, leave,
                    leaveX + (SEAT_LIST_W - font.width(leave)) / 2, buttonY + 4,
                    0xFFFFFFFF, false);
        }
    }

    private Component rightButtonLabel(RouletteStatePayload state) {
        if (!state.isSeated()) {
            // Offered during the lockout too. Sitting commits nothing, and
            // the newcomer just bets from the next round.
            return state.table().hasFreeSeat()
                    ? Component.translatable("tablegames.table.sit_down")
                    : Component.translatable("tablegames.table.full_short");
        }
        if (state.locked()) {
            return Component.translatable("tablegames.roulette.no_more_bets");
        }
        return amIReady(state)
                ? Component.translatable("tablegames.table.not_ready")
                : Component.translatable("tablegames.table.ready");
    }

    private static int rightButtonColor(RouletteStatePayload state) {
        if (!state.isSeated()) {
            return state.table().hasFreeSeat() ? 0xFF2E7D32 : 0xFF4A4A4A;
        }
        if (state.locked()) {
            return 0xFF4A4A4A;
        }
        return amIReady(state) ? 0xFFB8860B : 0xFF2E7D32;
    }

    private static boolean amIReady(RouletteStatePayload state) {
        int seat = state.table().mySeat();
        List<RouletteStatePayload.SeatView> seats = state.table().seats();
        return seat >= 0 && seat < seats.size() && seats.get(seat).ready();
    }

    /**
     * The other people at the table, down the side of the felt.
     * <p>
     * A tick beside a name is that player having declared themselves
     * finished, which is the only thing that lets the round skip its clock —
     * so it has to be visible, or a table waits thirty seconds without ever
     * being told why.
     */
    private void renderSeats(GuiGraphics graphics, RouletteStatePayload state) {
        int x = left + SEAT_LIST_X;
        int y = top + SEAT_LIST_Y;
        List<RouletteStatePayload.SeatView> seats = state.table().seats();

        Component heading = Component.translatable("tablegames.table.seats",
                seats.size(), state.table().maxSeats());
        graphics.drawString(font, heading, x, y, 0xFF9A9A9A, false);
        y += 10;

        for (RouletteStatePayload.SeatView seat : seats) {
            String line = (seat.ready() ? "✓ " : "· ") + seat.name();
            graphics.drawString(font, line, x, y,
                    seat.ready() ? 0xFF7ED27E : 0xFFD8D8D8, false);
            if (seat.staked() > 0) {
                String staked = format(seat.staked());
                graphics.drawString(font, staked,
                        x + SEAT_LIST_W - font.width(staked), y, 0xFFE0B33A, false);
            }
            y += 10;
        }

        if (state.table().spectatorCount() > 0) {
            graphics.drawString(font, Component.translatable(
                            "tablegames.table.watching", state.table().spectatorCount()),
                    x, y + 2, 0xFF7A7A7A, false);
        }
    }

    private void drawLabeledButton(GuiGraphics graphics, int mouseX, int mouseY,
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
                // Picking a chip clears the field. Leaving both sets would
                // mean the highlighted chip was not what a click would stake.
                customAmount.setValue("");
                click();
                return true;
            }
        }

        int buttonY = top + BUTTON_ROW_Y;
        if (isOver(mx, my, buttonX(0), buttonY, BUTTON_W, CHIP_H)) {
            send(RouletteActionPayload.clear(tablePos));
            return true;
        }
        RouletteStatePayload seated = ClientRouletteState.state();
        if (seated.isSeated() && !seated.locked()
                && isOver(mx, my, left + SEAT_LIST_X, buttonY, SEAT_LIST_W, CHIP_H)) {
            sendSeatAction(TableActionPayload.KIND_STAND);
            return true;
        }
        if (isOver(mx, my, buttonX(1), buttonY, BUTTON_W, CHIP_H)) {
            RouletteStatePayload state = ClientRouletteState.state();
            if (!state.isSeated()) {
                sendSeatAction(TableActionPayload.KIND_SIT);
            } else if (!state.locked()) {
                sendSeatAction(amIReady(state)
                        ? TableActionPayload.KIND_NOT_READY
                        : TableActionPayload.KIND_READY);
            }
            return true;
        }

        RouletteStatePayload current = ClientRouletteState.state();
        if (current.bettingOpen() && current.isSeated()) {
            for (Spot spot : spots) {
                if (spot.contains(mx, my)) {
                    send(RouletteActionPayload.place(tablePos, spot.type(),
                            spot.type().requiresTarget() ? pocketOf(spot) : null,
                            stakeToPlace()));
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

    private static int colorOf(Spot spot) {
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

    private void sendSeatAction(int kind) {
        PacketDistributor.sendToServer(new TableActionPayload(kind, tablePos));
        click();
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