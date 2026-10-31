package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.platform.game.Games;
import com.github.arrivedbog593.tablegames.platform.network.ChooseGamePayload;
import com.github.arrivedbog593.tablegames.platform.network.OpenGamePickerPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first step of setting up a table: which game it hosts.
 * <p>
 * Games come grouped by what they are a variant of. A game with no
 * variants is one button that picks it; a family of several — the two
 * roulette wheels, the blackjack formats — is one button that opens a second
 * list, of its modes, with a way back. That keeps the first list one line
 * per game however many ways each can be played.
 * <p>
 * Only games this player may host here are offered: games against the house
 * go to operators and casino admins alone, because they spend the house's
 * money. Picking one turns the table into that game straight away, and the
 * server answers with its settings screen; the table stays closed to players
 * until those are saved.
 * <p>
 * A plain {@link Screen} for the same reason as {@link TableConfigScreen}:
 * closing it is not leaving a table anybody was sitting at.
 */
public class TableGamePickerScreen extends Screen {

    private static final int PANEL_W = 300;
    private static final int PAD = 8;
    private static final int CONTROL_H = 16;
    private static final int LIST_Y = 36;
    /** A game's button, the line under it describing the game, and a gap. */
    private static final int ROW_H = 32;
    private static final int BUTTON_W = 90;
    private static final int BUTTON_GAP = 6;

    private static final int HINT = 0xFF707070;

    private final BlockPos tablePos;

    /** What the server offered, by family, in the order it offered them. */
    private final Map<String, List<Game>> families = new LinkedHashMap<>();

    /** The family whose modes are on show, or null for the list of games. */
    private String openFamily;

    private int left;
    private int top;
    private int panelHeight;

    /** Set once a game is sent, so a double click cannot send two. */
    private boolean chosen;

    /** One button: a game to pick, or a family to open. */
    private record Entry(Component name, Component about, Game game, String family) {
    }

    public TableGamePickerScreen(OpenGamePickerPayload payload) {
        super(Component.translatable("tablegames.picker.title"));
        this.tablePos = payload.tablePos();
        // Only what the server offered this player; a game it did not send
        // is one they would be refused.
        for (String id : payload.gameIds()) {
            Games.registry().get(id).ifPresent(game ->
                    families.computeIfAbsent(game.family(), family -> new ArrayList<>()).add(game));
        }
    }

    public static void open(OpenGamePickerPayload payload) {
        Minecraft.getInstance().setScreen(new TableGamePickerScreen(payload));
    }

    @Override
    protected void init() {
        super.init();
        panelHeight = LIST_Y + Math.max(1, entries().size()) * ROW_H + CONTROL_H + 12;
        left = (width - PANEL_W) / 2;
        top = (height - panelHeight) / 2;
    }

    /** The buttons on show: the games and families, or one family's modes. */
    private List<Entry> entries() {
        List<Entry> entries = new ArrayList<>();
        if (openFamily != null) {
            for (Game game : families.getOrDefault(openFamily, List.of())) {
                entries.add(new Entry(Component.translatable(game.modeTranslationKey()), describe(game), game, null));
            }
            return entries;
        }
        for (Map.Entry<String, List<Game>> family : families.entrySet()) {
            List<Game> games = family.getValue();
            if (games.size() == 1) {
                Game game = games.getFirst();
                entries.add(new Entry(Component.translatable(game.translationKey()), describe(game), game, null));
            } else {
                entries.add(new Entry(Component.translatable(games.getFirst().familyTranslationKey()),
                        Component.translatable("tablegames.picker.modes", games.size()), null, family.getKey()));
            }
        }
        return entries;
    }

    private void show(String family) {
        openFamily = family;
        // The panel's height follows the list, so the layout starts over.
        rebuildWidgets();
    }

    // --- Drawing ----------------------------------------------------------------

    @Override
    public void renderBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, left, top, left + PANEL_W, top + panelHeight);
    }

    @Override
    public void render(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        Component heading = openFamily == null ? title
                : Component.translatable(families.get(openFamily).getFirst().familyTranslationKey());
        graphics.drawString(font, heading, left + (PANEL_W - font.width(heading)) / 2, top + 7,
                Panels.LABEL_TEXT, false);
        Component prompt = Component.translatable(openFamily == null
                ? "tablegames.picker.prompt" : "tablegames.picker.prompt_mode");
        graphics.drawString(font, prompt, left + (PANEL_W - font.width(prompt)) / 2, top + 20,
                HINT, false);

        List<Entry> entries = entries();
        if (entries.isEmpty()) {
            // Wrapped: it explains why the list is empty, which takes a
            // sentence more than the panel is wide.
            List<FormattedCharSequence> lines = font.split(
                    Component.translatable("tablegames.picker.none"), PANEL_W - 2 * PAD);
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), left + PAD, top + LIST_Y + i * 10,
                        HINT, false);
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int x = left + PAD;
            int y = rowY(i);
            int w = PANEL_W - 2 * PAD;
            // A family opens a list rather than choosing, and looks it.
            Panels.button(graphics, x, y, w, CONTROL_H, entry.game() == null ? 0xFF2E6B45 : 0xFF3A5B8A,
                    !chosen && isOver(mouseX, mouseY, x, y, w, CONTROL_H));
            Component name = entry.game() == null
                    ? entry.name().copy().append(" ›") : entry.name();
            graphics.drawString(font, name, x + (w - font.width(name)) / 2, y + 4,
                    0xFFFFFFFF, false);
            graphics.drawString(font, entry.about(), x + (w - font.width(entry.about())) / 2,
                    y + CONTROL_H + 3, HINT, false);
        }

        if (openFamily != null) {
            drawFooterButton(graphics, mouseX, mouseY, backX(), CommonComponents.GUI_BACK, 0xFF6A6A6A);
        }
        drawFooterButton(graphics, mouseX, mouseY, cancelX(), CommonComponents.GUI_CANCEL, 0xFF8A3A3A);
    }

    private void drawFooterButton(GuiGraphics graphics, int mouseX, int mouseY, int x, Component label,
                                  int face) {
        int y = footerY();
        Panels.button(graphics, x, y, BUTTON_W, CONTROL_H, face, isOver(mouseX, mouseY, x, y, BUTTON_W, CONTROL_H));
        graphics.drawString(font, label, x + (BUTTON_W - font.width(label)) / 2, y + 4, 0xFFFFFFFF, false);
    }

    /** How many sit at it and who the money is played against. */
    private static Component describe(Game game) {
        Component players = game.minPlayers() == game.maxPlayers()
                ? Component.translatable("tablegames.picker.players_exact", game.maxPlayers())
                : Component.translatable("tablegames.picker.players",
                game.minPlayers(), game.maxPlayers());
        if (!game.usesBetting()) {
            return players;
        }
        return players.copy().append(" · ").append(Component.translatable(
                game.isHouseBanked()
                        ? "tablegames.table.house_banked"
                        : "tablegames.table.player_versus_player"));
    }

    private int rowY(int index) {
        return top + LIST_Y + index * ROW_H;
    }

    /** Alone, Cancel sits in the middle; beside Back, on the right. */
    private int cancelX() {
        return openFamily == null
                ? left + (PANEL_W - BUTTON_W) / 2
                : left + PANEL_W / 2 + BUTTON_GAP / 2;
    }

    private int backX() {
        return left + PANEL_W / 2 - BUTTON_GAP / 2 - BUTTON_W;
    }

    private int footerY() {
        return top + panelHeight - 8 - CONTROL_H;
    }

    // --- Input ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (!chosen) {
            List<Entry> entries = entries();
            for (int i = 0; i < entries.size(); i++) {
                if (!isOver(mx, my, left + PAD, rowY(i), PANEL_W - 2 * PAD, CONTROL_H)) {
                    continue;
                }
                Entry entry = entries.get(i);
                click();
                if (entry.game() == null) {
                    show(entry.family());
                    return true;
                }
                chosen = true;
                // No closing here: the server answers with the settings
                // screen, which takes this one's place.
                PacketDistributor.sendToServer(new ChooseGamePayload(tablePos, entry.game().id()));
                return true;
            }
        }
        if (openFamily != null && isOver(mx, my, backX(), footerY(), BUTTON_W, CONTROL_H)) {
            click();
            show(null);
            return true;
        }
        if (isOver(mx, my, cancelX(), footerY(), BUTTON_W, CONTROL_H)) {
            click();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** Escape in a family's modes goes back to the games, not out. */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && openFamily != null) {
            show(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
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
