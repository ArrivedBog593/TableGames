package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;
import com.github.arrivedbog593.tablegames.platform.economy.CreditFormat;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.network.ChooseGamePayload;
import com.github.arrivedbog593.tablegames.platform.network.OpenTableConfigPayload;
import com.github.arrivedbog593.tablegames.platform.network.TableConfigPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Setting up the game a table hosts, the second step after choosing it.
 * <p>
 * Built from the game's {@link SettingSpec}s rather than knowing any game: an
 * amount gets a text box, a flag an on/off button, a choice a button that
 * cycles. A game that declares a new setting shows up here without this class
 * changing.
 * <p>
 * Checks everything the server will, with the same engine code, so a bad
 * number is pointed at before it is sent. The server checks again anyway.
 * <p>
 * Saving is what opens the table for play. Leaving without saving keeps it
 * closed, and the next click on it comes back here. {@code Back} returns the
 * table to hosting nothing and shows the list of games again.
 * <p>
 * A plain {@link Screen} rather than a {@link TableScreen}: closing this is
 * not leaving the table, and must not start a seated player's absence clock.
 */
public class TableConfigScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PAD = 8;
    private static final int CONTROL_H = 16;
    private static final int GAME_ROW_Y = 20;
    private static final int SETTINGS_Y = 38;
    /** A label line, the control under it, and a gap. */
    private static final int ROW_H = 32;
    private static final int AMOUNT_W = 110;
    private static final int TOGGLE_W = 140;
    private static final int BUTTON_W = 68;
    private static final int BUTTON_GAP = 4;

    private static final int BUTTON_BACK = 0;
    private static final int BUTTON_DEFAULTS = 1;
    private static final int BUTTON_CANCEL = 2;
    private static final int BUTTON_SAVE = 3;
    private static final int BUTTON_COUNT = 4;

    private static final int AMBER = 0xFFB07000;
    private static final int ERROR = 0xFFB02020;
    private static final int HINT = 0xFF707070;

    private final BlockPos tablePos;
    private final String gameId;
    private final Optional<Game> game;
    private final TableSettings stored;

    /** Whether players can use the table already, which makes going back cost a round. */
    private final boolean configured;

    /** Set by a first click on Back at a live table; the second click goes. */
    private boolean confirmingBack;

    /**
     * Whether there is a list of games to go back to.
     * <p>
     * Only a table has one. A slot machine is its game, so the button was
     * offering a door that was not there — it asked for confirmation and
     * then sent a request the server dropped on the floor, which is the
     * worst of both: it looked like it worked.
     */
    private final boolean changeable;

    /**
     * What the player has entered so far, by setting id. Survives the rows
     * being rebuilt on a resize.
     */
    private final Map<String, String> amountDrafts = new HashMap<>();
    private final Map<String, Long> pickDrafts = new HashMap<>();

    private final List<Row> rows = new ArrayList<>();

    private int left;
    private int top;
    private int panelHeight;

    /** One setting on screen. {@code box} is null unless the spec is an amount. */
    private record Row(SettingSpec spec, int y, EditBox box) {
    }

    /** What the draft amounts to: the settings to send, or why not. */
    private record Check(TableSettings settings, Component problem, SettingSpec culprit) {
        boolean ok() {
            return problem == null;
        }
    }

    public TableConfigScreen(OpenTableConfigPayload payload) {
        // A machine is not a table, and calling it one in its own title is
        // the sort of small wrongness that makes a player doubt the rest.
        super(Component.translatable(payload.changeable()
                ? "tablegames.config.title" : "tablegames.config.title_machine"));
        this.tablePos = payload.tablePos();
        this.gameId = payload.gameId();
        this.game = Games.registry().get(gameId);
        this.stored = TableSettings.of(payload.values());
        this.configured = payload.configured();
        this.changeable = payload.changeable();
    }

    public static void open(OpenTableConfigPayload payload) {
        Minecraft.getInstance().setScreen(new TableConfigScreen(payload));
    }

    private List<SettingSpec> specs() {
        return game.map(Game::settings).orElse(List.of());
    }

    @Override
    protected void init() {
        super.init();
        List<SettingSpec> specs = specs();
        // One row's worth of height even with nothing to set, for the line
        // that says so, and room under the rows for a two-line message.
        panelHeight = SETTINGS_Y + Math.max(1, specs.size()) * ROW_H + 50;
        left = (width - PANEL_W) / 2;
        top = (height - panelHeight) / 2;

        rows.clear();
        for (int i = 0; i < specs.size(); i++) {
            SettingSpec spec = specs.get(i);
            int y = top + SETTINGS_Y + i * ROW_H;
            EditBox box = null;
            if (spec instanceof SettingSpec.Amount) {
                box = new EditBox(font, left + PAD, y + 11, AMOUNT_W, CONTROL_H,
                        Component.translatable(spec.translationKey()));
                // Thirteen digits covers the largest balance the economy holds.
                box.setMaxLength(13);
                box.setFilter(text -> text.chars().allMatch(Character::isDigit));
                box.setValue(amountDrafts.computeIfAbsent(spec.id(),
                        id -> String.valueOf(stored.get(spec))));
                box.setResponder(text -> amountDrafts.put(spec.id(), text));
                addRenderableWidget(box);
            } else {
                pickDrafts.computeIfAbsent(spec.id(), id -> stored.get(spec));
            }
            rows.add(new Row(spec, y, box));
        }
    }

    // --- Checking ---------------------------------------------------------------

    private Check check() {
        if (game.isEmpty()) {
            // A game this client does not know. Nothing it could send would
            // mean anything, so saving stays off.
            return new Check(stored, Component.translatable("tablegames.config.unknown_game"),
                    null);
        }
        TableSettings draft = stored;
        for (Row row : rows) {
            SettingSpec spec = row.spec();
            long value;
            // The messages leave out which setting: the culprit's box turns
            // red, and its label would not fit on the line beside the numbers.
            if (spec instanceof SettingSpec.Amount amount) {
                String text = amountDrafts.getOrDefault(spec.id(), "");
                try {
                    value = Long.parseLong(text);
                } catch (NumberFormatException notANumber) {
                    return new Check(draft, Component.translatable(
                            "tablegames.config.not_a_number"), spec);
                }
                if (!amount.accepts(value)) {
                    return new Check(draft, Component.translatable(
                            "tablegames.config.out_of_range",
                            CreditFormat.of(amount.minimum()),
                            CreditFormat.of(amount.maximum())), spec);
                }
            } else {
                value = pickDrafts.get(spec.id());
            }
            draft = draft.with(spec, value);
        }
        Optional<String> problem = game.get().settingsProblem(draft);
        if (problem.isPresent()) {
            return new Check(draft, Component.translatable(problem.get()), null);
        }
        return new Check(draft, null, null);
    }

    // --- Drawing ----------------------------------------------------------------

    /** The panel goes here so that the widgets super.render draws land on top of it. */
    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, left, top, left + PANEL_W, top + panelHeight);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Check check = check();
        for (Row row : rows) {
            if (row.box() != null) {
                row.box().setTextColor(row.spec() == check.culprit() ? 0xFFFF6060 : 0xFFE0E0E0);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 7,
                Panels.LABEL_TEXT, false);

        renderGameRow(graphics);
        renderRows(graphics, mouseX, mouseY);
        renderFooter(graphics, mouseX, mouseY, check);
    }

    /** Which game this is, as a heading: changing it is what Back is for. */
    private void renderGameRow(GuiGraphics graphics) {
        Component name = game
                .map(chosen -> (Component) Component.translatable(chosen.translationKey()))
                .orElse(Component.literal(gameId));
        Component line = Component.translatable("tablegames.config.game_named", name);
        graphics.drawString(font, line, left + (PANEL_W - font.width(line)) / 2,
                top + GAME_ROW_Y, 0xFF3A5B8A, false);
    }

    private void renderRows(GuiGraphics graphics, int mouseX, int mouseY) {
        if (rows.isEmpty()) {
            graphics.drawString(font, Component.translatable("tablegames.config.nothing_to_set"),
                    left + PAD, top + SETTINGS_Y + 4, HINT, false);
            return;
        }
        for (Row row : rows) {
            SettingSpec spec = row.spec();
            graphics.drawString(font, Component.translatable(spec.translationKey()),
                    left + PAD, row.y(), Panels.LABEL_TEXT, false);

            int controlY = row.y() + 11;
            if (spec instanceof SettingSpec.Amount) {
                Component fallback = Component.translatable("tablegames.config.default",
                        CreditFormat.of(spec.defaultValue()));
                graphics.drawString(font, fallback, left + PAD + AMOUNT_W + 8, controlY + 4,
                        HINT, false);
                continue;
            }
            int x = left + PAD;
            Panels.button(graphics, x, controlY, TOGGLE_W, CONTROL_H, 0xFF6A6A6A,
                    isOver(mouseX, mouseY, x, controlY, TOGGLE_W, CONTROL_H));
            Component shown = pickLabel(spec, pickDrafts.get(spec.id()));
            graphics.drawString(font, shown, x + (TOGGLE_W - font.width(shown)) / 2,
                    controlY + 4, 0xFFFFFFFF, false);
        }
    }

    private static Component pickLabel(SettingSpec spec, long value) {
        if (spec instanceof SettingSpec.Flag flag) {
            return flag.isOn(value) ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }
        if (spec instanceof SettingSpec.Choice choice) {
            return Component.translatable(choice.translationKeyFor(value));
        }
        return Component.literal(String.valueOf(value));
    }

    private int footerY() {
        return top + panelHeight - 8 - CONTROL_H;
    }

    private void renderFooter(GuiGraphics graphics, int mouseX, int mouseY, Check check) {
        if (confirmingBack) {
            // Going back stands everybody up and drops the round, which is
            // worth saying before it happens rather than after.
            drawMessage(graphics, Component.translatable("tablegames.config.back_confirm"), AMBER);
        } else if (!check.ok()) {
            drawMessage(graphics, check.problem(), ERROR);
        } else if (!configured) {
            drawMessage(graphics, Component.translatable("tablegames.config.save_to_open"), AMBER);
        }

        if (changeable) {
            drawFooterButton(graphics, mouseX, mouseY, BUTTON_BACK,
                    Component.translatable("tablegames.config.back"),
                    confirmingBack ? 0xFFB07000 : 0xFF6A6A6A, true);
        }
        drawFooterButton(graphics, mouseX, mouseY, BUTTON_DEFAULTS,
                Component.translatable("tablegames.config.defaults"), 0xFF6A6A6A,
                !rows.isEmpty());
        drawFooterButton(graphics, mouseX, mouseY, BUTTON_CANCEL, CommonComponents.GUI_CANCEL,
                0xFF8A3A3A, true);
        drawFooterButton(graphics, mouseX, mouseY, BUTTON_SAVE,
                Component.translatable("tablegames.config.save"), 0xFF2E7D32, check.ok());
    }

    /** Wrapped to the panel, at most two lines, sitting just above the buttons. */
    private void drawMessage(GuiGraphics graphics, Component message, int color) {
        List<FormattedCharSequence> lines = font.split(message, PANEL_W - 2 * PAD);
        int count = Math.min(2, lines.size());
        int y = footerY() - 4 - count * 10;
        for (int i = 0; i < count; i++) {
            graphics.drawString(font, lines.get(i), left + PAD, y + i * 10, color, false);
        }
    }

    private void drawFooterButton(GuiGraphics graphics, int mouseX, int mouseY, int index,
                                  Component label, int face, boolean enabled) {
        int x = footerButtonX(index);
        int y = footerY();
        Panels.button(graphics, x, y, BUTTON_W, CONTROL_H, enabled ? face : 0xFF4A4A4A,
                enabled && isOver(mouseX, mouseY, x, y, BUTTON_W, CONTROL_H));
        graphics.drawString(font, label, x + (BUTTON_W - font.width(label)) / 2, y + 4,
                enabled ? 0xFFFFFFFF : 0xFF9A9A9A, false);
    }

    /** The buttons spread across the bottom, right aligned. */
    private int footerButtonX(int index) {
        int fromRight = BUTTON_COUNT - index;
        return left + PANEL_W - PAD - fromRight * BUTTON_W - (fromRight - 1) * BUTTON_GAP;
    }

    // --- Input ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        boolean back = button == 1;

        for (Row row : rows) {
            if (row.box() != null) {
                continue;
            }
            int controlY = row.y() + 11;
            if (isOver(mx, my, left + PAD, controlY, TOGGLE_W, CONTROL_H)) {
                cycle(row.spec(), back);
                click();
                return true;
            }
        }

        int footerY = footerY();
        if (changeable
                && isOver(mx, my, footerButtonX(BUTTON_BACK), footerY, BUTTON_W, CONTROL_H)) {
            click();
            goBack();
            return true;
        }
        // Anything else clicked calls off a pending Back.
        confirmingBack = false;
        if (isOver(mx, my, footerButtonX(BUTTON_DEFAULTS), footerY, BUTTON_W, CONTROL_H)
                && !rows.isEmpty()) {
            resetToDefaults();
            click();
            return true;
        }
        if (isOver(mx, my, footerButtonX(BUTTON_CANCEL), footerY, BUTTON_W, CONTROL_H)) {
            click();
            onClose();
            return true;
        }
        if (isOver(mx, my, footerButtonX(BUTTON_SAVE), footerY, BUTTON_W, CONTROL_H)) {
            save();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Returns the table to hosting nothing. The server answers with the list
     * of games, which takes this screen's place.
     * <p>
     * A table nobody could use yet goes back on the first click. A live one
     * asks for a second, because the round and every seat go with it.
     */
    private void goBack() {
        if (configured && !confirmingBack) {
            confirmingBack = true;
            return;
        }
        PacketDistributor.sendToServer(new ChooseGamePayload(tablePos, ""));
    }

    private void cycle(SettingSpec spec, boolean back) {
        long current = pickDrafts.get(spec.id());
        int count = spec instanceof SettingSpec.Choice choice ? choice.options().size() : 2;
        pickDrafts.put(spec.id(), (long) Math.floorMod(current + (back ? -1 : 1), count));
    }

    private void resetToDefaults() {
        for (Row row : rows) {
            SettingSpec spec = row.spec();
            if (row.box() != null) {
                row.box().setValue(String.valueOf(spec.defaultValue()));
            } else {
                pickDrafts.put(spec.id(), spec.defaultValue());
            }
        }
    }

    private void save() {
        Check check = check();
        if (!check.ok()) {
            return;
        }
        Map<String, Long> values = new LinkedHashMap<>();
        for (Row row : rows) {
            values.put(row.spec().id(), check.settings().get(row.spec()));
        }
        PacketDistributor.sendToServer(new TableConfigPayload(tablePos, gameId, values));
        click();
        onClose();
    }

    // --- Housekeeping -----------------------------------------------------------

    @Override
    public void tick() {
        super.tick();
        if (TablePresence.lost(minecraft, tablePos)) {
            onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void click() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }
}
