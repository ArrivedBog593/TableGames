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

import java.util.ArrayList;
import java.util.List;

/**
 * The first step of setting up a table: which game it hosts.
 * <p>
 * One button per game this player may host here, nothing else to decide:
 * games against the house are offered only to operators and casino admins,
 * because they spend the house's money. Picking one turns
 * the table into that game straight away, and the server answers with its
 * settings screen; the table stays closed to players until those are saved.
 * <p>
 * A plain {@link Screen} for the same reason as {@link TableConfigScreen}:
 * closing it is not leaving a table anybody was sitting at.
 */
public class TableGamePickerScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PAD = 8;
    private static final int CONTROL_H = 16;
    private static final int LIST_Y = 36;
    /** A game's button, the line under it describing the game, and a gap. */
    private static final int ROW_H = 32;
    private static final int BUTTON_W = 90;

    private static final int HINT = 0xFF707070;

    private final BlockPos tablePos;
    private final List<Game> games = new ArrayList<>();

    private int left;
    private int top;
    private int panelHeight;

    /** Set once a game is sent, so a double click cannot send two. */
    private boolean chosen;

    public TableGamePickerScreen(OpenGamePickerPayload payload) {
        super(Component.translatable("tablegames.picker.title"));
        this.tablePos = payload.tablePos();
        // Only what the server offered this player; a game it did not send
        // is one they would be refused.
        for (String id : payload.gameIds()) {
            Games.registry().get(id).ifPresent(games::add);
        }
    }

    public static void open(OpenGamePickerPayload payload) {
        Minecraft.getInstance().setScreen(new TableGamePickerScreen(payload));
    }

    @Override
    protected void init() {
        super.init();
        panelHeight = LIST_Y + Math.max(1, games.size()) * ROW_H + CONTROL_H + 12;
        left = (width - PANEL_W) / 2;
        top = (height - panelHeight) / 2;
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

        graphics.drawString(font, title, left + (PANEL_W - font.width(title)) / 2, top + 7,
                Panels.LABEL_TEXT, false);
        Component prompt = Component.translatable("tablegames.picker.prompt");
        graphics.drawString(font, prompt, left + (PANEL_W - font.width(prompt)) / 2, top + 20,
                HINT, false);

        if (games.isEmpty()) {
            // Wrapped: it explains why the list is empty, which takes a
            // sentence more than the panel is wide.
            List<FormattedCharSequence> lines = font.split(
                    Component.translatable("tablegames.picker.none"), PANEL_W - 2 * PAD);
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                graphics.drawString(font, lines.get(i), left + PAD, top + LIST_Y + i * 10,
                        HINT, false);
            }
        }
        for (int i = 0; i < games.size(); i++) {
            Game game = games.get(i);
            int x = left + PAD;
            int y = rowY(i);
            int w = PANEL_W - 2 * PAD;
            Panels.button(graphics, x, y, w, CONTROL_H, 0xFF3A5B8A,
                    !chosen && isOver(mouseX, mouseY, x, y, w, CONTROL_H));
            Component name = Component.translatable(game.translationKey());
            graphics.drawString(font, name, x + (w - font.width(name)) / 2, y + 4,
                    0xFFFFFFFF, false);

            Component about = describe(game);
            graphics.drawString(font, about, x + (w - font.width(about)) / 2,
                    y + CONTROL_H + 3, HINT, false);
        }

        int cancelX = cancelX();
        int cancelY = cancelY();
        Panels.button(graphics, cancelX, cancelY, BUTTON_W, CONTROL_H, 0xFF8A3A3A,
                isOver(mouseX, mouseY, cancelX, cancelY, BUTTON_W, CONTROL_H));
        Component cancel = CommonComponents.GUI_CANCEL;
        graphics.drawString(font, cancel, cancelX + (BUTTON_W - font.width(cancel)) / 2,
                cancelY + 4, 0xFFFFFFFF, false);
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

    private int cancelX() {
        return left + (PANEL_W - BUTTON_W) / 2;
    }

    private int cancelY() {
        return top + panelHeight - 8 - CONTROL_H;
    }

    // --- Input ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int mx = (int) mouseX;
        int my = (int) mouseY;
        if (!chosen) {
            for (int i = 0; i < games.size(); i++) {
                if (isOver(mx, my, left + PAD, rowY(i), PANEL_W - 2 * PAD, CONTROL_H)) {
                    chosen = true;
                    click();
                    // No closing here: the server answers with the settings
                    // screen, which takes this one's place.
                    PacketDistributor.sendToServer(
                            new ChooseGamePayload(tablePos, games.get(i).id()));
                    return true;
                }
            }
        }
        if (isOver(mx, my, cancelX(), cancelY(), BUTTON_W, CONTROL_H)) {
            click();
            onClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
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
