package com.github.arrivedbog593.tablegames.engine.game;

import com.github.arrivedbog593.tablegames.engine.session.GameSession;
import com.github.arrivedbog593.tablegames.engine.session.Seat;
import com.github.arrivedbog593.tablegames.engine.table.SettingSpec;
import com.github.arrivedbog593.tablegames.engine.table.TableSettings;

import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * A game the mod knows how to run: its rules metadata plus a factory for
 * sessions.
 * <p>
 * Implementations are stateless singletons registered once in
 * {@link GameRegistry}. All mutable per-hand states live in the
 * {@link GameSession} this creates. Never store hand data on a Game.
 * <p>
 * Adding a new game means writing one Game and one GameSession. Nothing in
 * the core has to change.
 */
public interface Game {

    /**
     * Stable identifier, lowercase with underscores, e.g. {@code "roulette"}.
     * Persisted in table block entities, so renaming it breaks saved worlds.
     */
    String id();

    /** Translation key for the display name. */
    default String translationKey() {
        return "tablegames.game." + id();
    }

    /** Fewer players needed to start. Solo house games return 1. */
    int minPlayers();

    /** Most players who can sit at once. */
    int maxPlayers();

    /**
     * Whether this game moves credits at all. Uno played casually returns
     * false; poker and blackjack return true.
     */
    boolean usesBetting();

    /**
     * Whether players compete against the house rather than each other.
     * <p>
     * This is the single most important flag for server economy safety. A
     * house game mints credits when it loses, so the platform layer must
     * check the house balance and enforce a table maximum before letting one
     * start. Player-versus-player games only move chips between seats and can
     * never break the economy.
     */
    boolean isHouseBanked();

    /** Smallest legal wager, in credits. Ignored when betting is off. */
    default long minimumBet() {
        return 0;
    }

    /**
     * What a table hosting this game can be configured with, in the order a
     * screen should offer it.
     * <p>
     * Empty by default, which is the honest answer for a game with nothing to
     * decide. Ids must be prefixed with {@link #id()} so that two games
     * asking for the same thing do not share one another's answers.
     */
    default List<SettingSpec> settings() {
        return List.of();
    }

    /**
     * Whatever is wrong with these settings taken together, as a translation
     * key, or empty when they are usable.
     * <p>
     * Each setting already validates itself against its own bounds. This is
     * for the rules that span two of them — a ceiling that may not sit below
     * its own floor — which no single spec can see. Checked before a
     * configuration is accepted, so a table never stores a set of numbers its
     * own game would refuse to start on.
     */
    default Optional<String> settingsProblem(TableSettings settings) {
        return Optional.empty();
    }

    /**
     * Creates a session for one hand.
     *
     * @param seats  players, in turn order, must satisfy the player bounds
     * @param random the server's generator, or a seeded one in tests
     */
    GameSession createSession(List<Seat> seats, RandomGenerator random);

    /** Convenience check the table block runs before dealing. */
    default boolean canStartWith(int playerCount) {
        return playerCount >= minPlayers() && playerCount <= maxPlayers();
    }
}