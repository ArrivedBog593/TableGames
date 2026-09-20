package com.github.arrivedbog593.tablegames.client;

import com.github.arrivedbog593.tablegames.platform.network.OpenTableScreenPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Which screen belongs to which game.
 * <p>
 * The client's half of opening a table. Adding blackjack means writing its
 * screen and adding one line here; nothing else in the mod has to know.
 * <p>
 * Client only.
 */
public final class TableScreens {

    private static final Map<String, Function<BlockPos, net.minecraft.client.gui.screens.Screen>>
            SCREENS = new LinkedHashMap<>();

    static {
        // The screen has to be told which game it is drawing. One class
        // serves both wheels, but they differ in the title and in whether
        // the layout carries a double zero, and a bare constructor reference
        // gave it no way to know either.
        SCREENS.put("roulette",
                pos -> new RouletteScreen(pos, "roulette", false));
        SCREENS.put("american_roulette",
                pos -> new RouletteScreen(pos, "american_roulette", true));

        // Not a table at all: its own cabinet, opened the same way, because
        // what the server sends is the game and not the block.
        SCREENS.put("slots", SlotMachineScreen::new);
    }

    private TableScreens() {
    }

    public static void open(OpenTableScreenPayload payload) {
        var factory = SCREENS.get(payload.gameId());
        if (factory == null) {
            return;
        }
        Minecraft.getInstance().setScreen(factory.apply(payload.tablePos()));
    }
}
