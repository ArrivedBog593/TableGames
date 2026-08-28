package com.github.arrivedbog593.tablegames.platform.game;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.game.GameRegistry;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.platform.block.TableVariant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The server's game registry and the look each game gives a table.
 * <p>
 * Plain Java rather than a Minecraft registry: the engine has to stay
 * testable without launching the game, and nothing about a game needs
 * network syncing or datapack overriding. The block entity stores a game id
 * as text and resolves it through here.
 * <p>
 * Adding a game means one line in {@link #bootstrap()}. That is the whole
 * point of the split.
 */
public final class Games {

    private static final GameRegistry REGISTRY = new GameRegistry();
    private static final Map<String, TableVariant> VARIANTS = new LinkedHashMap<>();
    private static boolean ready;

    private Games() {
    }

    /** Registers every built-in game. Called once during mod construction. */
    public static void bootstrap() {
        if (ready) {
            return;
        }
        register(RouletteGame.european(), TableVariant.WHEEL);
        register(RouletteGame.american(), TableVariant.WHEEL);
        REGISTRY.freeze();
        ready = true;
    }

    private static void register(Game game, TableVariant variant) {
        REGISTRY.register(game);
        VARIANTS.put(game.id(), variant);
    }

    public static GameRegistry registry() {
        return REGISTRY;
    }

    /** How a table hosting this game should look. */
    public static TableVariant variantOf(Game game) {
        return game == null
                ? TableVariant.BLANK
                : VARIANTS.getOrDefault(game.id(), TableVariant.BLANK);
    }
}