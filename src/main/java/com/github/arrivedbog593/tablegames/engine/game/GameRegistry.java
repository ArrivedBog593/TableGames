package com.github.arrivedbog593.tablegames.engine.game;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of every game the mod can run, keyed by {@link Game#id()}.
 * <p>
 * Plain Java on purpose, not a Minecraft registry: the engine must stay
 * testable without launching the game. The platform layer creates one of
 * these during mod setup and registers the built-in games into it.
 * <p>
 * Not thread-safe. Register everything during startup, then only read.
 */
public final class GameRegistry {

    private final Map<String, Game> byId = new LinkedHashMap<>();
    private boolean frozen;

    /**
     * Registers a game.
     *
     * @throws IllegalStateException    if the registry is frozen
     * @throws IllegalArgumentException if the id is malformed or already taken
     */
    public void register(Game game) {
        Objects.requireNonNull(game, "game");
        if (frozen) {
            throw new IllegalStateException("Registry is frozen; register during setup only");
        }
        String id = game.id();
        if (id == null || !id.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid game id: " + id);
        }
        if (game.minPlayers() < 1 || game.maxPlayers() < game.minPlayers()) {
            throw new IllegalArgumentException("Invalid player bounds for game " + id);
        }
        Game existing = byId.putIfAbsent(id, game);
        if (existing != null) {
            throw new IllegalArgumentException("Duplicate game id: " + id);
        }
    }

    /** Closes the registry to further registration. Call after setup. */
    public void freeze() {
        frozen = true;
    }

    public Optional<Game> get(String id) {
        return Optional.ofNullable(byId.get(
                id == null ? null : id.toLowerCase(Locale.ROOT)));
    }

    /** Every registered game, in registration order. */
    public Collection<Game> all() {
        return java.util.Collections.unmodifiableCollection(byId.values());
    }

    public boolean contains(String id) {
        return get(id).isPresent();
    }

    public int size() {
        return byId.size();
    }
}