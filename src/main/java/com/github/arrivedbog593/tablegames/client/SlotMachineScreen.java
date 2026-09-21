package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.games.slots.Payline;
import com.github.arrivedbog593.tablegames.engine.games.slots.Paytable;
import com.github.arrivedbog593.tablegames.engine.games.slots.Reel;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotMachine;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotSymbol;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.platform.network.PlayerFunds;
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

import java.util.Arrays;
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
 * The margins either side of the glass carry the paytable, priced at the
 * stake currently set rather than as multipliers. A cabinet that asks for
 * money without saying what it pays is asking the player to take its word
 * for it, and the two columns fit exactly the eight combinations there are.
 * <p>
 * The reels are animated off a local clock. While the server says they are
 * turning it has not sent what they landed on, so what scrolls past is the
 * client's own noise; the real symbols arrive when they stop. They arrive
 * all at once, which is why the glass keeps moving for a moment afterwards:
 * each drum is slowed onto its own result in turn, left to right, and until
 * the last one is down nothing else on the panel admits what happened.
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
    private static final int PANEL_H = 278;

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

    /**
     * One color per payline, without alpha; how bright a line is drawn is
     * decided where it is drawn. Distinct enough that two lines crossing the
     * same cell stay two lines.
     */
    private static final int[] LINE_COLORS = {
            0x00E0B020, 0x004FA8E8, 0x005ECC5E, 0x00E06AB0, 0x00E88030,
    };

    /** One paytable column, in the margin beside the glass. */
    private static final int PAY_COL_W = 65;
    private static final int PAY_ROW_H = 22;

    /**
     * What the glass lists, best first, down the left margin and on down the
     * right.
     * <p>
     * Every combination that pays is here — there are eight and there is room
     * for eight — because a paytable that left one out would be worse than
     * none at all. The odd one is two coal, which pays on the first two reels
     * whatever the third shows; it is why the count is drawn and not assumed.
     */
    private static final int[][] PAY_ROWS = {
            {SlotSymbol.NETHERITE.ordinal(), 3},
            {SlotSymbol.DIAMOND.ordinal(), 3},
            {SlotSymbol.EMERALD.ordinal(), 3},
            {SlotSymbol.GOLD.ordinal(), 3},
            {SlotSymbol.IRON.ordinal(), 3},
            {SlotSymbol.COAL.ordinal(), 3},
            {SlotSymbol.COAL.ordinal(), 2},
            {SlotSymbol.REPLAY.ordinal(), 3},
    };

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

    /** How far the drum moves from one symbol to the next: a cell and its gap. */
    private static final int PITCH = CELL + CELL_GAP;

    /**
     * How long one symbol takes to pass on each reel.
     * <p>
     * Three different rates, so the drums read as three separate things
     * rather than one picture being shuffled. What scrolls past has no
     * relation to what will land, and could not have: the landing symbols
     * are not on this client until the reels stop.
     */
    private static final long[] STEP_MILLIS = {80, 95, 110};

    /**
     * How long a reel takes to come up to speed from standing.
     * <p>
     * Long enough to watch. A quarter of a second is a cut, not a pull: the
     * drum is at its top speed before the eye has found it, and the whole
     * spin then reads as one constant blur with a stop bolted on each end.
     * At this length the rise is its own part of the animation, and a third
     * of the time the server holds the spin for.
     */
    private static final long SPIN_UP_MILLIS = 700;

    /** The wait between one reel beginning to slow and the next beginning to. */
    private static final long STOP_STAGGER_MILLIS = 120;

    /**
     * How many symbols a reel spends slowing down.
     * <p>
     * The knob that decides whether the stop reads as a stop. Distance, not
     * time, because time is what falls out of it: a reel sheds a fixed number
     * of symbols at constant deceleration, so the slower drums take longer to
     * do it, exactly as a heavier one would. Five is enough that the last
     * symbol alone takes about half the slowdown, which is the part the
     * eye actually reads as coasting.
     */
    private static final int SETTLE_SYMBOLS = 5;

    /**
     * How long the glass keeps moving after the server says the spin is over.
     * <p>
     * The worst case rather than a figure of its own, because each reel works
     * out its own slowing from how far it has to go: the last one to start is
     * also the slowest, and the furthest it can be asked to travel is one
     * symbol more than {@link #SETTLE_SYMBOLS}.
     */
    private static final long LANDING_MILLIS = STOP_STAGGER_MILLIS * (SlotMachine.REELS - 1)
            + 2L * (SETTLE_SYMBOLS + 1) * Arrays.stream(STEP_MILLIS).max().orElse(0L);

    /** The buy-in prompt, while it is open. Everything under it is covered. */
    private BuyInPrompt prompt;

    /**
     * Which line button the mouse is over, or -1. Worked out once a frame
     * because the glass is drawn before the buttons are and has to know.
     */
    private int hoveredLine = -1;

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

    /** What the last pull came to, between the lever and the meters. */
    private int resultY() {
        return meterY() - 12;
    }

    /** The two meters, above the buttons that change them. */
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

        hoveredLine = -1;
        for (int i = 0; prompt == null && i < Payline.MAX; i++) {
            if (isOver(mouseX, mouseY, lineButtonX(i), lineButtonY(), LINE_BUTTON_W, BUTTON_H)) {
                hoveredLine = i;
            }
        }

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
        drawPaytable(graphics, state);
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

        SlotsStatePayload.SpinView spin = state.spin();
        boolean asleep = !state.machine().rolling() && !spin.hasResult();
        // A rendered item ignores anything drawn flat over it afterwards, so
        // while the prompt is up the symbols are not drawn at all rather than
        // drawn and covered. They showed through the prompt otherwise.
        boolean covered = prompt != null;

        if (reelsMoving(state)) {
            drawTurningReels(graphics, x0, y0, spin, covered);
        } else {
            drawRestingReels(graphics, x0, y0, spin, asleep, covered);
        }
        if (!covered) {
            drawPaylines(graphics, state);
        }
    }

    /**
     * Whether the glass is still moving — either the server says so, or it
     * has just stopped saying so and a reel has yet to come down.
     * <p>
     * Everything that would give the result away asks this rather than the
     * packet: the win on the meter, the lit cells, the lever. A machine that
     * announced the money while its drums were still turning would be a
     * machine whose reels nobody ever watched again.
     */
    private boolean reelsMoving(SlotsStatePayload state) {
        if (state.machine().rolling()) {
            return true;
        }
        long stopped = ClientSlotsState.sinceRollStopped();
        return state.spin().hasResult() && stopped >= 0 && stopped < LANDING_MILLIS;
    }

    /** Where a reel is this frame, and where its result sits on the drum. */
    private record ReelAt(double scroll, int resultAt) {

        /** No result on this drum yet: everything on it is the client's noise. */
        static final int NOWHERE = Integer.MIN_VALUE;
    }

    /**
     * The drums, turning.
     * <p>
     * One tall strip per reel rather than three cells, because a symbol
     * halfway between two rows has to be drawn halfway between two rows.
     * The window clips it and the cabinet's frame is painted back over the
     * top afterwards, so what shows through is a drum behind a bezel instead
     * of three pictures being swapped.
     */
    private void drawTurningReels(GuiGraphics graphics, int x0, int y0,
                                  SlotsStatePayload.SpinView spin, boolean covered) {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            int x = x0 + reel * PITCH;
            graphics.fill(x, y0, x + CELL, y0 + WINDOW_H, 0xFF1B1B1B);
        }
        if (covered) {
            return;
        }

        long stopped = ClientSlotsState.sinceRollStopped();
        graphics.enableScissor(x0, y0, x0 + WINDOW_W, y0 + WINDOW_H);
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            int x = x0 + reel * PITCH;
            ReelAt at = positionOf(reel, stopped);
            int whole = (int) Math.floor(at.scroll() / PITCH);
            int within = (int) Math.round(at.scroll() - (double) whole * PITCH);
            // One slot above the window as well, so a symbol is already on
            // its way in rather than appearing at the top edge.
            for (int slot = -1; slot < Reel.ROWS; slot++) {
                SlotSymbol symbol = stripSymbol(reel, slot - whole, at.resultAt(), spin);
                drawSymbol(graphics, symbol, x, y0 + slot * PITCH + within);
            }
        }
        graphics.disableScissor();

        // The bars between the rows, over the symbols. A rendered item
        // ignores anything drawn flat after it unless it is given a depth,
        // which is why these take the z the plain overloads do not.
        for (int row = 1; row < Reel.ROWS; row++) {
            int y = y0 + row * PITCH - CELL_GAP;
            graphics.fill(x0, y, x0 + WINDOW_W, y + CELL_GAP, 300, REEL_BACK);
        }
    }

    /** The drums, stopped: what they landed on, or a dark cabinet. */
    private void drawRestingReels(GuiGraphics graphics, int x0, int y0,
                                  SlotsStatePayload.SpinView spin, boolean asleep, boolean covered) {
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            for (int row = 0; row < Reel.ROWS; row++) {
                int x = x0 + reel * PITCH;
                int y = y0 + row * PITCH;
                boolean lit = !asleep && onWinningLine(spin, reel, row);
                graphics.fill(x, y, x + CELL, y + CELL, lit ? 0xFF2A2410 : 0xFF1B1B1B);
                if (lit) {
                    graphics.renderOutline(x, y, CELL, CELL, LINE_LIT);
                }
                if (covered) {
                    continue;
                }
                drawSymbol(graphics, asleep
                        ? stripSymbol(reel, row, ReelAt.NOWHERE, spin)
                        : spin.symbolAt(reel, row), x, y);
                if (asleep) {
                    // Above the item's own depth, which is why this takes the
                    // z the flat overloads do not.
                    graphics.fill(x, y, x + CELL, y + CELL, 300, 0xC4121212);
                }
            }
        }
    }

    private void drawSymbol(GuiGraphics graphics, SlotSymbol symbol, int x, int y) {
        ItemStack icon = new ItemStack(FACES.getOrDefault(symbol, Items.COAL));
        graphics.renderItem(icon, x + (CELL - 16) / 2, y + (CELL - 16) / 2);
    }

    /**
     * The lines being played, drawn across the glass where they run.
     * <p>
     * Without this a player is asked to choose between "3" and "4" with
     * nothing on screen saying what either one is. The numbers alone are a
     * private joke between the button and the paytable.
     * <p>
     * The lines in play are drawn faintly all the time, and whichever button
     * the mouse is over is drawn bright — so the way to find out what line
     * four is, is to point at the four.
     */
    private void drawPaylines(GuiGraphics graphics, SlotsStatePayload state) {
        int chosen = ClientSlotsState.lines();
        for (int i = 0; i < Payline.MAX; i++) {
            boolean playing = i < chosen;
            boolean pointed = i == hoveredLine;
            if (!playing && !pointed) {
                continue;
            }
            // A line that paid is drawn brightest of all: it is the reason
            // the player is looking at the glass at all.
            boolean paid = !state.machine().rolling() && state.spin().hasResult()
                    && state.spin().paid(Payline.values()[i]);
            int alpha = pointed || paid ? 0xFF000000 : 0x55000000;
            drawPayline(graphics, Payline.values()[i], LINE_COLORS[i] | alpha);
        }
    }

    private void drawPayline(GuiGraphics graphics, Payline line, int color) {
        int x0 = windowX();
        int y0 = windowY();
        int[] xs = new int[SlotMachine.REELS];
        int[] ys = new int[SlotMachine.REELS];
        for (int reel = 0; reel < SlotMachine.REELS; reel++) {
            xs[reel] = x0 + reel * (CELL + CELL_GAP) + CELL / 2;
            ys[reel] = y0 + line.rowOn(reel) * (CELL + CELL_GAP) + CELL / 2;
        }
        // Out to the edges of the glass at both ends, so a line reads as
        // crossing the window rather than as three dots joined up.
        segment(graphics, x0 - 2, ys[0], xs[0], ys[0], color);
        for (int reel = 0; reel + 1 < SlotMachine.REELS; reel++) {
            segment(graphics, xs[reel], ys[reel], xs[reel + 1], ys[reel + 1], color);
        }
        segment(graphics, xs[SlotMachine.REELS - 1], ys[SlotMachine.REELS - 1],
                x0 + WINDOW_W + 2, ys[SlotMachine.REELS - 1], color);
    }

    /**
     * A straight run of pixels from one point to another.
     * <p>
     * Drawn above the symbols, because a payline that ran behind them would
     * be hidden exactly where it matters. There is no line primitive to call
     * and the runs here are short, so it steps along the longer axis.
     */
    private static void segment(GuiGraphics graphics, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            int x = x1 + (x2 - x1) * i / steps;
            int y = y1 + (y2 - y1) * i / steps;
            graphics.fill(x, y, x + 1, y + 2, 400, color);
        }
    }

    /**
     * The paytable, in the two margins the glass leaves.
     * <p>
     * In credits at the stake currently set, not as multipliers, because
     * "three coal pays seven times" is an arithmetic problem and "three coal
     * pays 21" is an answer. Change the per-line stake and the whole column
     * moves with it, which is also the clearest way to see what raising it
     * actually buys.
     * <p>
     * It is a payout for <em>one line</em>. Lines do not interact — each is
     * read and paid on its own — so a figure per line is the whole truth and
     * playing five of them simply gives five chances at it.
     */
    private void drawPaytable(GuiGraphics graphics, SlotsStatePayload state) {
        if (prompt != null) {
            return;
        }
        Paytable paytable = paytableOf(state);
        long perLine = ClientSlotsState.perLine();
        SlotSymbol[] all = SlotSymbol.values();
        int rows = PAY_ROWS.length / 2;

        for (int i = 0; i < PAY_ROWS.length; i++) {
            SlotSymbol symbol = all[PAY_ROWS[i][0]];
            int count = PAY_ROWS[i][1];
            boolean rightHand = i >= rows;
            int x = rightHand ? left + PANEL_W - PAD - PAY_COL_W : left + PAD;
            int y = windowY() + (i % rows) * PAY_ROW_H;

            // How many of the symbol is the stack size on the icon, which is
            // a number Minecraft already knows how to draw in a corner. It
            // costs no width, so a wide payout can never crowd it out — and
            // two coal and three coal sit next to each other wearing the same
            // picture, so the count is the only thing telling them apart.
            ItemStack icon = new ItemStack(FACES.getOrDefault(symbol, Items.COAL), count);
            graphics.renderItem(icon, x, y);
            graphics.renderItemDecorations(font, icon, x, y);

            Component pays = payoutOf(symbol, count, paytable, perLine);
            graphics.drawString(font, pays, x + PAY_COL_W - font.width(pays), y + 4,
                    symbol == SlotSymbol.REPLAY ? FREE_TEXT : LABEL_TEXT, false);
        }
    }

    /**
     * What a combination pays, in credits, at the stake currently set.
     * <p>
     * Read off the same paytable the server runs, found by the return this
     * machine says it gives. The client is not told the multipliers and does
     * not need to be: the four levels are part of the game, the same on both
     * sides, and a screen that was sent them could disagree with the reels.
     */
    private Component payoutOf(SlotSymbol symbol, int count, Paytable paytable, long perLine) {
        if (symbol == SlotSymbol.REPLAY) {
            return Component.translatable("tablegames.slots.pay_free");
        }
        int multiple = count == 2 ? paytable.twoCoal() : paytable.multipleFor(symbol);
        return Component.literal(format(Math.max(0, multiple * perLine)));
    }

    private static Paytable paytableOf(SlotsStatePayload state) {
        for (SlotsGame.Payback level : SlotsGame.Payback.values()) {
            if (level.percent().equals(state.machine().payback())) {
                return level.paytable();
            }
        }
        // A machine that has not said what it returns yet. The defaults are
        // the right thing to show, and are what it will run if nobody says
        // otherwise.
        return SlotsGame.Payback.P95.paytable();
    }

    /** The speed a reel cruises at once it is up to it, in pixels per millisecond. */
    private static double cruiseOf(int reel) {
        return PITCH / (double) STEP_MILLIS[reel % STEP_MILLIS.length];
    }

    /**
     * How fast a reel is turning this many milliseconds into its pull.
     * <p>
     * A smoothstep up to the cruise rather than a straight ramp. Constant
     * acceleration reaches full speed and then stops accelerating in the same
     * instant, and that corner is visible — the drum arrives at its top speed
     * with a flick. This leaves standing gently and settles onto the cruise
     * gently, because it is flat at both ends.
     */
    private static double speedAt(int reel, long millis) {
        if (millis <= 0) {
            return 0;
        }
        double cruise = cruiseOf(reel);
        if (millis >= SPIN_UP_MILLIS) {
            return cruise;
        }
        double u = millis / (double) SPIN_UP_MILLIS;
        return cruise * u * u * (3 - 2 * u);
    }

    /**
     * How far a reel has turned, in pixels, this many milliseconds in.
     * <p>
     * The integral of {@link #speedAt}. The smoothstep covers half of what
     * the cruise would have in the same time, which is what lets the second
     * line stay the plain one: past the ramp the drum is simply cruising,
     * half a ramp behind where it would be had it never had to start.
     */
    private static double traveled(int reel, long millis) {
        if (millis <= 0) {
            return 0;
        }
        double cruise = cruiseOf(reel);
        if (millis < SPIN_UP_MILLIS) {
            double u = millis / (double) SPIN_UP_MILLIS;
            return cruise * SPIN_UP_MILLIS * u * u * u * (1 - u / 2.0);
        }
        return cruise * (millis - SPIN_UP_MILLIS / 2.0);
    }

    /**
     * Where one reel is, and what it is coming to rest on.
     * <p>
     * Worked out from the two clocks every frame rather than stepped along,
     * so nothing has to be remembered between frames and a dropped frame
     * costs nothing. While the reel is still turning there is no result on
     * its drum to find.
     * <p>
     * Once it begins to slow it is given somewhere to stop — the next whole
     * symbol {@link #SETTLE_SYMBOLS} further on — and it coasts there under
     * **constant deceleration**, which is the whole of why this reads as a
     * reel winding down rather than a reel being switched off.
     * <p>
     * Constant deceleration is the one curve that leaves the cruise at
     * exactly the speed the drum was already turning and arrives at exactly
     * zero. Anything eased more sharply has to start the slowdown *faster*
     * than the spin to cover the same ground in the same time, and a drum
     * that speeds up before it stops does not look like it is stopping at
     * all. The cost is that the duration stops being ours to pick: it falls
     * out of the distance and the speed, so a slower drum takes longer.
     */
    private static ReelAt positionOf(int reel, long stopped) {
        long spinning = Math.max(0, ClientSlotsState.sinceRollStarted());
        long settling = stopped < 0 ? -1 : stopped - STOP_STAGGER_MILLIS * reel;
        if (settling <= 0) {
            return new ReelAt(traveled(reel, spinning), ReelAt.NOWHERE);
        }

        double from = traveled(reel, spinning - settling);
        int restingAt = (int) Math.floor(from / PITCH) + SETTLE_SYMBOLS + 1;
        double to = (double) restingAt * PITCH;

        // From v to nothing at a steady rate covers v·t/2, so the time this
        // reel needs is twice the distance over the speed it is leaving at.
        // Read off the ramp rather than assumed to be the cruise: a spin
        // short enough that a reel is told to stop before it got up to speed
        // would otherwise be handed a slowdown that starts faster than it was
        // going, which is the very thing this curve exists to avoid.
        double entry = speedAt(reel, spinning - settling);
        double budget = LANDING_MILLIS - STOP_STAGGER_MILLIS * reel;
        double slowing = Math.min(budget, 2.0 * (to - from) / entry);
        double progress = Math.min(1.0, settling / slowing);
        double eased = 1 - (1 - progress) * (1 - progress);
        return new ReelAt(from + (to - from) * eased, -restingAt);
    }

    /**
     * What sits at one place on a reel's drum.
     * <p>
     * A fixed hash of the reel and the place rather than the symbol list
     * read in order, so the drum is the same drum each time round instead of
     * a ladder climbing through coal, iron, gold. None of what it returns
     * means anything while the reel is turning: this client has not been
     * told what the spin landed on, which is the point of withholding it.
     * <p>
     * From the moment a reel starts slowing, the three places it is coming
     * to rest on hold the real result — so the symbols the player watches
     * drop into the window are the ones that stay in it.
     */
    private static SlotSymbol stripSymbol(int reel, int at, int resultAt,
                                          SlotsStatePayload.SpinView spin) {
        if (resultAt != ReelAt.NOWHERE) {
            int row = at - resultAt;
            if (row >= 0 && row < Reel.ROWS) {
                return spin.symbolAt(reel, row);
            }
        }
        SlotSymbol[] all = SlotSymbol.values();
        int mixed = reel * 0x9E3779B9 + at * 0x85EBCA6B;
        mixed ^= mixed >>> 15;
        return all[Math.floorMod(mixed, all.length)];
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
        SlotsStatePayload.SpinView spin = state.spin();
        PlayerFunds funds = state.funds();

        // Read off the cabinet, not out of the viewer's pocket: these are the
        // numbers on the front of the machine, and somebody watching over a
        // player's shoulder can see them in a real casino too.
        // The server stops withholding the winnings the moment it stops
        // saying the reels turn, which is a few hundred milliseconds before
        // they stop turning here. Held back the rest of the way, so the
        // meter does not call the result over the top of the drums.
        boolean moving = reelsMoving(state);
        long onMeter = funds.inCredits(state.machine().seat().credits());
        long banked = moving ? 0 : funds.inCredits(spin.prizes());
        Component credits = Component.translatable("tablegames.slots.credits",
                format(Math.max(0, onMeter - banked)));
        graphics.drawString(font, credits, left + PAD, meterY(), LABEL_TEXT, false);

        Component prizes = Component.translatable("tablegames.slots.prizes", format(banked));
        graphics.drawString(font, prizes, left + PANEL_W - PAD - font.width(prizes), meterY(),
                banked > 0 ? WIN_TEXT : LABEL_TEXT, false);

        if (moving || !spin.hasResult()) {
            return;
        }
        long won = funds.inCredits(spin.won());
        Component result = won > 0
                ? Component.translatable("tablegames.slots.won", format(won))
                : Component.translatable("tablegames.slots.lost");
        graphics.drawString(font, result, left + (PANEL_W - font.width(result)) / 2, resultY(),
                won > 0 ? WIN_TEXT : HINT, false);
    }

    /**
     * Insert credits, add more, or cash out — unless somebody else is at the
     * machine, in which case it says so and offers nothing.
     * <p>
     * A watcher is not shown a button they cannot use. They are shown whose
     * machine it is, which is the answer to the question they would have
     * clicked it to find out.
     */
    private void drawSeatButtons(GuiGraphics graphics, SlotsStatePayload state,
                                 int mouseX, int mouseY) {
        int y = seatButtonY();
        int x = left + PAD;
        boolean seated = state.isSeated();

        if (state.isWatching()) {
            Component playing = Component.translatable("tablegames.slots.in_use_by",
                    state.machine().seat().player());
            graphics.drawString(font, playing,
                    left + (PANEL_W - font.width(playing)) / 2, y + 4, HINT, false);
            return;
        }

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
        // One button, two meanings, in the order a player wants them: while
        // there is anything in the prize bank it takes that and leaves them
        // playing, and only once the bank is empty does it mean "I am done".
        // Pressing it twice cashes out everything, which is what somebody
        // walking away will do without being told.
        boolean banked = state.spin().hasPrizes();
        int outX = left + PANEL_W - PAD - SEAT_BUTTON_W;
        Component out = Component.translatable(banked
                ? "tablegames.slots.collect" : "tablegames.slots.cash_out");
        boolean outHovered = isOver(mouseX, mouseY, outX, y, SEAT_BUTTON_W, BUTTON_H);
        drawButton(graphics, outX, y, SEAT_BUTTON_W, BUTTON_H,
                banked ? 0xFF2E7D32 : PANEL, outHovered);
        graphics.drawString(font, out, outX + (SEAT_BUTTON_W - font.width(out)) / 2, y + 4,
                banked ? 0xFFFFFFFF : LABEL_TEXT, false);
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
            PacketDistributor.sendToServer(SlotsActionPayload.spin(
                    tablePos, ClientSlotsState.lines(), ClientSlotsState.perLine()));
            click();
            return true;
        }

        int seatY = seatButtonY();
        // A watcher has no buttons down here, so the row is not clickable for
        // them either. Letting the prompt open would only earn them a refusal
        // after typing an amount into it.
        if (!state.isWatching()
                && isOver(mx, my, left + PAD, seatY, SEAT_BUTTON_W, BUTTON_H)) {
            openPrompt(state.isSeated());
            click();
            return true;
        }
        if (state.isSeated()
                && isOver(mx, my, left + PANEL_W - PAD - SEAT_BUTTON_W, seatY,
                        SEAT_BUTTON_W, BUTTON_H)) {
            if (state.spin().hasPrizes()) {
                PacketDistributor.sendToServer(SlotsActionPayload.collect(tablePos));
                click();
            } else {
                sendSeatAction(TableActionPayload.KIND_STAND, 0);
            }
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
        // Dead while the glass is still coming down as well as while the
        // server is turning it. The pull would be legal — the spin is over —
        // but it would cut the landing short, and a lever that takes money
        // for a result the player never saw is the wrong kind of fast.
        if (!state.isSeated() || state.machine().roundPhase().isCountingDown()
                || reelsMoving(state)) {
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
