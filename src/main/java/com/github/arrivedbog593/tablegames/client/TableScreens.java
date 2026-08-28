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
        SCREENS.put("roulette", RouletteScreen::new);
        SCREENS.put("american_roulette", RouletteScreen::new);
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
