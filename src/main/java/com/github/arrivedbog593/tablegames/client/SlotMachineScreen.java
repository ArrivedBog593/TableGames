package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.games.slots.Payline;
import com.github.arrivedbog593.tablegames.engine.games.slots.Reel;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotSymbol;
import com.github.arrivedbog593.tablegames.platform.network.SlotsActionPayload;
import com.github.arrivedbog593.tablegames.platform.network.SlotsStatePayload;
import com.github.arrivedbog593.tablegames.platform.network.TableActionPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.Map;

/**
 * The face of a slot machine: three reels, the lines you are playing, what
 * a line costs, and the lever.
 * <p>
 * Far smaller than the roulette felt and for the same reason the cabinet is
 * its own block — there is one player, one move, and nothing to watch
 * somebody else do. Everything on it is a control for the next pull or a
 * figure about the last one.
 * <p>
 * The symbols are drawn as the items they are named for. Nothing in the
 * engine knows that: {@link SlotSymbol} is a list of distinct things, and
 * which picture stands for which is decided here, where pictures belong.
 * <p>
 * The reels are animated off a local clock. While the server says they are
 * turning it has not sent what they landed on, so what scrolls past is the
 * client's own noise; the real symbols arrive when they stop.
 */
public class SlotMachineScreen extends TableScreen {

    private static final int PANEL_W = 248;

    /**
     * Tall enough for everything below the glass to stand clear of
     * everything else.
     * <p>
     * Worked out from the rows rather than guessed: the window, the line
     * buttons, the stake row, the lever, what it costs, the meter, and the
     * two credit buttons, each with room to breathe. Guessing it is how the
     * meter ended up printed through the stake row.
     */
    private static final int PANEL_H = 272;

    private static final int PAD = 10;

    /** One symbol's cell in the window. */
    private static final int CELL = 28;
    private static final int CELL_GAP = 3;

    private static final int WINDOW_W = SlotMachine.REELS * CELL + (SlotMachine.REELS - 1) * CELL_GAP;
    private static final int WINDOW_H = Reel.ROWS * CELL + (Reel.ROWS - 1) * CELL_GAP;

    private static final int BUTTON_H = 16;
    private static final int LINE_BUTTON_W = 20;
    private static final int SPIN_W = 76;

    /** Wide enough for "Agregar créditos", which is the longest label here. */
    private static final int SEAT_BUTTON_W = 100;

    private static final int HINT = 0xFF707070;
    private static final int WIN_TEXT = 0xFF1E7A1E;
    private static final int FREE_TEXT = 0xFF9A6400;
    private static final int REEL_BACK = 0xFF101010;
    private static final int LINE_LIT = 0xFFE0B020;

    /** Which item stands for each symbol. */
    private static final Map<SlotSymbol, Item> FACES = new EnumMap<>(SlotSymbol.class);

    static {
        FACES.put(SlotSymbol.COAL, Items.COAL);
        FACES.put(SlotSymbol.IRON, Items.IRON_INGOT);
        FACES.put(SlotSymbol.GOLD, Items.GOLD_INGOT);
        FACES.put(SlotSymbol.EMERALD, Items.EMERALD);
        FACES.put(SlotSymbol.DIAMOND, Items.DIAMOND);
        FACES.put(SlotSymbol.NETHERITE, Items.NETHERITE_INGOT);
        // Not a material at all, which is the point: a replay is the one
        // symbol that pays in spins rather than credits.
        FACES.put(SlotSymbol.REPLAY, Items.CLOCK);
    }

    /**
     * How long one symbol stays in place on each reel while it turns.
     * <p>
     * Three different rates, so the drums read as three separate things
     * rather than one picture being shuffled. What scrolls past has no
     * relation to what will land, and could not have: the landing symbols
     * are not on this client until the reels stop.
     */
    private static final long[] STEP_MILLIS = {70, 85, 100};

    /** The buy-in prompt, while it is open. Everything under it is covered. */
    private BuyInPrompt prompt;

    public SlotMachineScreen(BlockPos machinePos) {
        super(Component.translatable("tablegames.slots.title"), machinePos, PANEL_W, PANEL_H);
    }

    // --- Layout ----------------------------------------------------------------

    private int windowX() {
        return left + (PANEL_W - WINDOW_W) / 2;
    }

    private int windowY() {
        return top + 34;
    }

    private int lineButtonY() {
        return windowY() + WINDOW_H + 12;
    }

    private int lineButtonX(int index) {
        int row = Payline.MAX * LINE_BUTTON_W + (Payline.MAX - 1) * 4;
        return left + (PANEL_W - row) / 2 + index * (LINE_BUTTON_W + 4);
    }

    private int stakeY() {
        return lineButtonY() + BUTTON_H + 10;
    }

    private int spinY() {
        return stakeY() + BUTTON_H + 14;
    }

    private int spinX() {
        return left + (PANEL_W - SPIN_W) / 2;
    }

    /** Under the lever: what the next pull costs. */
    private int costY() {
        return spinY() + BUTTON_H + 4;
    }

    /** The credit meter, above the buttons that change it. */
    private int meterY() {
        return seatButtonY() - 13;
    }

    private int seatButtonY() {
        return top + PANEL_H - PAD - BUTTON_H;
    }

    // --- Prompt ------------------------------------------------------------------

    @Override
    protected void init() {
        super.init();
        // A resize rebuilds every widget and moves the panel, so a prompt
        // that was open is laid out again where the panel now is.
        if (prompt != null) {
            openPrompt(prompt.isRebuy());
        }
    }

    private void openPrompt(boolean rebuy) {
        closePrompt();
        prompt = new BuyInPrompt(font, rebuy, left + PANEL_W / 2, top + PANEL_H / 2,
                () -> ClientSlotsState.state().funds(),
                amount -> {
                    sendSeatAction(rebuy ? TableActionPayload.KIND_REBUY
                            : TableActionPayload.KIND_SIT, amount);
                    closePrompt();
                },
                this::closePrompt);
        addWidget(prompt.box());
        setFocused(prompt.box());
    }

    private void closePrompt() {
        if (prompt != null) {
            removeWidget(prompt.box());
            prompt = null;
            setFocused(null);
        }
    }

    /** Drops the prompt once the seat it was about was taken, or lost. */
    @Override
    public void tick() {
        super.tick();
        if (prompt != null && prompt.isRebuy() != ClientSlotsState.isSeated()) {
            closePrompt();
        }
    }

    /** Enter confirms and Escape backs out of the prompt, not the machine. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (prompt != null) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                prompt.cancel();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                prompt.confirm();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // --- Drawing -------------------------------------------------------------------

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        SlotsStatePayload state = ClientSlotsState.state();

        drawPanel(graphics, left, top, left + PANEL_W, top + PANEL_H);
        graphics.drawString(font, title, left + PAD, top + PAD, LABEL_TEXT, false);
        if (!state.machine().payback().isEmpty()) {
            Component returns = Component.translatable("tablegames.slots.returns",
                    state.machine().payback());
            graphics.drawString(font, returns,
                    left + PANEL_W - PAD - font.width(returns), top + PAD, HINT, false);
        }
        // What a credit costs, under the return, because between them they
        // are the whole of what this cabinet is: how much a pull is and how
        // much of it comes back.
        if (state.funds().priced()) {
            Component each = Component.translatable("tablegames.slots.denomination",
                    format(state.funds().each()));
            graphics.drawString(font, each,
                    left + PANEL_W - PAD - font.width(each), top + PAD + 11, HINT, false);
        }

        drawWindow(graphics, state);
        drawLineButtons(graphics, mouseX, mouseY);
        drawStakeRow(graphics, state, mouseX, mouseY);
        drawSpin(graphics, state, mouseX, mouseY);
        drawMeter(graphics, state);
        drawSeatButtons(graphics, state, mouseX, mouseY);

        if (prompt != null) {
            graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xA0101010);
            prompt.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /**
     * The three reels: turning, showing what they landed on, or dark.
     * <p>
     * Dark is its own state and not a spin nobody made. A machine with
     * nothing on the glass yet used to show a frozen row of symbols, which
     * read as a result — the player's first sight of the cabinet was three
     * symbols they had not paid for and could not explain.
     */
    private void drawWindow(GuiGraphics graphics, SlotsStatePayload state) {
        int x0 = windowX();
        int y0 = windowY();
        graphics.fill(x0 - 3, y0 - 3, x0 + WINDOW_W + 3, y0 + WINDOW_H + 3, OUTLINE);
        graphics.fill(x0 - 2, y0 - 2, x0 + WINDOW_W + 2, y0 + WINDOW_H + 2, REEL_BACK);

        boolean rolling = state.machine().rolling();
        SlotsStatePayload.SpinView spin = state.spin();
        SlotSymbol[] all = SlotSymbol.values();
        boolean asleep = !rolling && !spin.hasResult();
        // A rendered item ignores anything drawn flat over it afterwards, so
        // while the prompt is up the symbols are not drawn at all rather than
        // drawn and covered. They showed through the prompt otherwise.
        boolean covered = prompt != null;

        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            for (int row = 0; row < Reel.ROWS; row++) {
                int x = x0 + reel * (CELL + CELL_GAP);
                int y = y0 + row * (CELL + CELL_GAP);
                boolean lit = !rolling && spin.hasResult() && onWinningLine(spin, reel, row);
                graphics.fill(x, y, x + CELL, y + CELL, lit ? 0xFF2A2410 : 0xFF1B1B1B);
                if (lit) {
                    graphics.renderOutline(x, y, CELL, CELL, LINE_LIT);
                }
                if (covered) {
                    continue;
                }

                SlotSymbol symbol = rolling || asleep
                        ? scrolling(all, reel, row)
                        : spin.symbolAt(reel, row);
                ItemStack icon = new ItemStack(FACES.getOrDefault(symbol, Items.COAL));
                graphics.renderItem(icon, x + (CELL - 16) / 2, y + (CELL - 16) / 2);
                if (asleep) {
                    // Above the item's own depth, which is why this takes the
                    // z the flat overloads do not.
                    graphics.fill(x, y, x + CELL, y + CELL, 300, 0xC4121212);
                }
            }
        }
    }

    /**
     * What a turning reel shows at this row, right now.
     * <p>
     * The three rows of one reel are consecutive symbols, and the whole
     * column steps along as time passes, so what the player sees is a strip
     * scrolling rather than three cells being randomised independently.
     * Each reel steps at its own rate and starts somewhere else along the
     * strip, which is what stops them moving as one block.
     */
    private static SlotSymbol scrolling(SlotSymbol[] all, int reel, int row) {
        long since = Math.max(0, ClientSlotsState.sinceRollStarted());
        long step = since / STEP_MILLIS[reel % STEP_MILLIS.length];
        // The offset per reel is prime to the symbol count, so the three
        // never line up into an accidental win nobody was paid for.
        long index = step + row + (long) reel * 5;
        return all[(int) Math.floorMod(index, all.length)];
    }

    private boolean onWinningLine(SlotsStatePayload.SpinView spin, int reel, int row) {
        for (Payline line : Payline.values()) {
            if (spin.paid(line) && line.rowOn(reel) == row) {
                return true;
            }
        }
        return false;
    }

    /** One button per line count: how many of the five to play. */
    private void drawLineButtons(GuiGraphics graphics, int mouseX, int mouseY) {
        Component label = Component.translatable("tablegames.slots.lines");
        graphics.drawString(font, label, left + PAD, lineButtonY() + 4, LABEL_TEXT, false);

        int chosen = ClientSlotsState.lines();
        for (int i = 0; i < Payline.MAX; i++) {
            int x = lineButtonX(i);
            boolean selected = chosen == i + 1;
            boolean hovered = isOver(mouseX, mouseY, x, lineButtonY(), LINE_BUTTON_W, BUTTON_H);
            drawButton(graphics, x, lineButtonY(), LINE_BUTTON_W, BUTTON_H,
                    selected ? LINE_LIT : PANEL, hovered);
            String text = String.valueOf(i + 1);
            graphics.drawString(font, text,
                    x + (LINE_BUTTON_W - font.width(text)) / 2, lineButtonY() + 4,
                    selected ? 0xFF201800 : LABEL_TEXT, false);
        }
    }

    /** Minus, the stake on each line, plus, and the biggest bet the machine takes. */
    private void drawStakeRow(GuiGraphics graphics, SlotsStatePayload state,
                              int mouseX, int mouseY) {
        int y = stakeY();
        Component label = Component.translatable("tablegames.slots.per_line");
        graphics.drawString(font, label, left + PAD, y + 4, LABEL_TEXT, false);

        int minusX = left + PANEL_W / 2 - 60;
        int plusX = left + PANEL_W / 2 + 40;
        drawButton(graphics, minusX, y, 20, BUTTON_H, PANEL,
                isOver(mouseX, mouseY, minusX, y, 20, BUTTON_H));
        graphics.drawString(font, "-", minusX + 8, y + 4, LABEL_TEXT, false);
        drawButton(graphics, plusX, y, 20, BUTTON_H, PANEL,
                isOver(mouseX, mouseY, plusX, y, 20, BUTTON_H));
        graphics.drawString(font, "+", plusX + 7, y + 4, LABEL_TEXT, false);

        drawRecess(graphics, minusX + 24, y, 72, BUTTON_H);
        String stake = format(ClientSlotsState.perLine());
        graphics.drawString(font, stake, minusX + 24 + (72 - font.width(stake)) / 2, y + 4,
                0xFFFFFFFF, false);

        int maxX = plusX + 24;
        boolean hovered = isOver(mouseX, mouseY, maxX, y, 44, BUTTON_H);
        drawButton(graphics, maxX, y, 44, BUTTON_H, PANEL, hovered);
        Component max = Component.translatable("tablegames.slots.max_bet");
        graphics.drawString(font, max, maxX + (44 - font.width(max)) / 2, y + 4,
                LABEL_TEXT, false);
    }

    private void drawSpin(GuiGraphics graphics, SlotsStatePayload state, int mouseX, int mouseY) {
        int x = spinX();
        int y = spinY();
        boolean ready = canSpin(state);
        boolean hovered = ready && isOver(mouseX, mouseY, x, y, SPIN_W, BUTTON_H);
        drawButton(graphics, x, y, SPIN_W, BUTTON_H, ready ? 0xFFB03020 : SLOT_FILL, hovered);

        Component label = state.spin().owesAFreeSpin()
                ? Component.translatable("tablegames.slots.free_spin")
                : Component.translatable("tablegames.slots.spin");
        graphics.drawString(font, label, x + (SPIN_W - font.width(label)) / 2, y + 4,
                ready ? 0xFFFFFFFF : 0xFF606060, false);

        // What the pull costs, under the lever, so nobody finds out after.
        // In credits, and beside it what those credits are worth, because
        // the balance is what the player actually pays with.
        Component cost;
        if (state.spin().owesAFreeSpin()) {
            cost = Component.translatable("tablegames.slots.free_hint");
        } else if (state.funds().priced()) {
            cost = Component.translatable("tablegames.slots.cost_priced",
                    format(spinCost()), format(state.funds().inBalance(spinCost())));
        } else {
            cost = Component.translatable("tablegames.slots.cost", format(spinCost()));
        }
        graphics.drawString(font, cost, left + (PANEL_W - font.width(cost)) / 2, costY(),
                state.spin().owesAFreeSpin() ? FREE_TEXT : HINT, false);
    }

    /**
     * The credit meter, and what the last pull came to.
     * <p>
     * Both in credits, which is the whole point of a denomination: once the
     * player has bought in, the machine stops talking about their balance
     * and talks in its own unit, the way one on a real floor does.
     */
    private void drawMeter(GuiGraphics graphics, SlotsStatePayload state) {
        int y = meterY();
        Component credits = Component.translatable("tablegames.slots.credits",
                format(state.funds().stackHeld()));
        graphics.drawString(font, credits, left + PAD, y, LABEL_TEXT, false);

        SlotsStatePayload.SpinView spin = state.spin();
        if (state.machine().rolling() || !spin.hasResult()) {
            return;
        }
        long won = state.funds().inCredits(spin.won());
        Component result = won > 0
                ? Component.translatable("tablegames.slots.won", format(won))
                : Component.translatable("tablegames.slots.lost");
        graphics.drawString(font, result, left + PANEL_W - PAD - font.width(result), y,
                won > 0 ? WIN_TEXT : HINT, false);
    }

    /** Insert credits, add more, or cash out: the same three acts as a table's seat. */
    private void drawSeatButtons(GuiGraphics graphics, SlotsStatePayload state,
                                 int mouseX, int mouseY) {
        int y = seatButtonY();
        int x = left + PAD;
        boolean seated = state.isSeated();

        Component first = seated
                ? Component.translatable("tablegames.slots.add_credits")
                : Component.translatable("tablegames.slots.insert_credits");
        boolean hovered = isOver(mouseX, mouseY, x, y, SEAT_BUTTON_W, BUTTON_H);
        drawButton(graphics, x, y, SEAT_BUTTON_W, BUTTON_H, PANEL, hovered);
        graphics.drawString(font, first, x + (SEAT_BUTTON_W - font.width(first)) / 2, y + 4,
                LABEL_TEXT, false);

        if (!seated) {
            return;
        }
        int outX = left + PANEL_W - PAD - SEAT_BUTTON_W;
        Component out = Component.translatable("tablegames.slots.cash_out");
        boolean outHovered = isOver(mouseX, mouseY, outX, y, SEAT_BUTTON_W, BUTTON_H);
        drawButton(graphics, outX, y, SEAT_BUTTON_W, BUTTON_H, PANEL, outHovered);
        graphics.drawString(font, out, outX + (SEAT_BUTTON_W - font.width(out)) / 2, y + 4,
                LABEL_TEXT, false);
    }

    // --- Clicking --------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;

        if (prompt != null) {
            if (prompt.mouseClicked(mx, my)) {
                return true;
            }
            // Clicks inside the prompt that hit nothing are swallowed; the
            // machine behind it does not get them.
            if (prompt.contains(mx, my)) {
                return true;
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        SlotsStatePayload state = ClientSlotsState.state();

        for (int i = 0; i < Payline.MAX; i++) {
            if (isOver(mx, my, lineButtonX(i), lineButtonY(), LINE_BUTTON_W, BUTTON_H)) {
                ClientSlotsState.setLines(i + 1);
                click();
                return true;
            }
        }

        int y = stakeY();
        int minusX = left + PANEL_W / 2 - 60;
        int plusX = left + PANEL_W / 2 + 40;
        if (isOver(mx, my, minusX, y, 20, BUTTON_H)) {
            ClientSlotsState.setPerLine(ClientSlotsState.perLine() - step(state));
            click();
            return true;
        }
        if (isOver(mx, my, plusX, y, 20, BUTTON_H)) {
            ClientSlotsState.setPerLine(ClientSlotsState.perLine() + step(state));
            click();
            return true;
        }
        if (isOver(mx, my, plusX + 24, y, 44, BUTTON_H)) {
            ClientSlotsState.setLines(Payline.MAX);
            ClientSlotsState.setPerLine(largestStake(state));
            click();
            return true;
        }

        if (isOver(mx, my, spinX(), spinY(), SPIN_W, BUTTON_H) && canSpin(state)) {
            PacketDistributor.sendToServer(new SlotsActionPayload(
                    tablePos, ClientSlotsState.lines(), ClientSlotsState.perLine()));
            click();
            return true;
        }

        int seatY = seatButtonY();
        if (isOver(mx, my, left + PAD, seatY, SEAT_BUTTON_W, BUTTON_H)) {
            openPrompt(state.isSeated());
            click();
            return true;
        }
        if (state.isSeated()
                && isOver(mx, my, left + PANEL_W - PAD - SEAT_BUTTON_W, seatY,
                        SEAT_BUTTON_W, BUTTON_H)) {
            sendSeatAction(TableActionPayload.KIND_STAND, 0);
            return true;
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    // --- What the buttons mean --------------------------------------------------------

    /** What the next pull costs, in credits. */
    private long spinCost() {
        return ClientSlotsState.perLine() * ClientSlotsState.lines();
    }

    /**
     * Whether the lever would do anything.
     * <p>
     * The server decides for real. This only keeps the button from lighting
     * up for a pull that is certain to come back refused.
     */
    private boolean canSpin(SlotsStatePayload state) {
        if (!state.isSeated() || state.machine().roundPhase().isCountingDown()) {
            return false;
        }
        return state.spin().owesAFreeSpin() || spinCost() <= state.funds().stackHeld();
    }

    /** How much one press of plus or minus moves the stake: the machine's minimum. */
    private static long step(SlotsStatePayload state) {
        return Math.max(1, state.machine().betMinimum());
    }

    /**
     * The most a line may be played for here, in credits, with what is on
     * the meter taken into account.
     */
    private static long largestStake(SlotsStatePayload state) {
        long ceiling = state.machine().betMaximum();
        long affordable = Math.max(state.machine().betMinimum(),
                state.funds().stackHeld() / Payline.MAX);
        return ceiling > 0 ? Math.min(ceiling, affordable) : affordable;
    }

    private void sendSeatAction(int kind, long amount) {
        PacketDistributor.sendToServer(new TableActionPayload(kind, tablePos, amount));
        click();
    }

    private void click() {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.4F, 1.0F);
        }
    }
}
