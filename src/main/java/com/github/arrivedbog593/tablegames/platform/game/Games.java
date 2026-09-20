package com.github.arrivedbog593.tablegames.platform.game;

import com.github.arrivedbog593.tablegames.engine.game.Game;
import com.github.arrivedbog593.tablegames.engine.game.GameRegistry;
import com.github.arrivedbog593.tablegames.engine.games.roulette.RouletteGame;
import com.github.arrivedbog593.tablegames.engine.games.slots.SlotsGame;
import com.github.arrivedbog593.tablegames.platform.block.TableVariant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The server's game registry, the look each game gives a table, and which
 * games belong on a table at all.
 * <p>
 * Plain Java rather than a Minecraft registry: the engine has to stay
 * testable without launching the game, and nothing about a game needs
 * network syncing or datapack overriding. The block entity stores a game id
 * as text and resolves it through here.
 * <p>
 * Adding a game means one line in {@link #bootstrap()}. That is the whole
 * point of the split.
 * <p>
 * Not every game goes on a table. A slot machine is a cabinet you stand at
 * alone, and a block shaped like a table opening one would be nonsense, so
 * it is registered as a machine instead: in the registry like any other
 * game, with settings and a payback of its own, but never offered to a table
 * and refused if a client asks for it there anyway. Its own block hosts it
 * and hosts nothing else.
 */
public final class Games {

    private static final GameRegistry REGISTRY = new GameRegistry();
    private static final Map<String, TableVariant> VARIANTS = new LinkedHashMap<>();
    private static boolean ready;

    private static SlotsGame slots;

    private Games() {
    }

    /** Registers every built-in game. Called once during mod construction. */
    public static void bootstrap() {
        if (ready) {
            return;
        }
        onTable(RouletteGame.european(), TableVariant.WHEEL);
        onTable(RouletteGame.american(), TableVariant.WHEEL);
        slots = inMachine(SlotsGame.standard());
        REGISTRY.freeze();
        ready = true;
    }

    private static void onTable(Game game, TableVariant variant) {
        REGISTRY.register(game);
        VARIANTS.put(game.id(), variant);
    }

    private static <T extends Game> T inMachine(T game) {
        REGISTRY.register(game);
        return game;
    }

    public static GameRegistry registry() {
        return REGISTRY;
    }

    /** The game a slot machine block hosts, the only one it ever hosts. */
    public static SlotsGame slots() {
        return slots;
    }

    /**
     * Whether a table may be set to this game.
     * <p>
     * Asked before a table is assigned anything, wherever the request came
     * from: the picker builds its list from this, and the command and the
     * payload both check it again, because a list a client was sent is not a
     * promise about what it will send back.
     */
    public static boolean fitsOnATable(Game game) {
        return game != null && VARIANTS.containsKey(game.id());
    }

    /** Every game a table can be set to, in registry order. */
    public static List<Game> tableGames() {
        List<Game> games = new ArrayList<>();
        for (Game game : REGISTRY.all()) {
            if (fitsOnATable(game)) {
                games.add(game);
            }
        }
        return games;
    }

    /** How a table hosting this game should look. */
    public static TableVariant variantOf(Game game) {
        return game == null
                ? TableVariant.BLANK
                : VARIANTS.getOrDefault(game.id(), TableVariant.BLANK);
    }
}
